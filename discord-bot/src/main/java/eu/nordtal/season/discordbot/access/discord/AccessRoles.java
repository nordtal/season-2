package eu.nordtal.season.discordbot.access.discord;

import static eu.nordtal.season.database.AdminTexts.TEXTS;
import static eu.nordtal.season.discordbot.AccessMessages.MESSAGES;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.database.access.AccessDirectory;
import eu.nordtal.season.database.access.AccessGrant;
import eu.nordtal.season.database.alert.Alert;
import eu.nordtal.season.database.alert.AlertType;
import eu.nordtal.season.database.alert.DiscordRole;
import eu.nordtal.season.discordbot.AdminLog;
import eu.nordtal.season.discordbot.LayoutRenderer;
import eu.nordtal.season.discordbot.config.AccessSpec;
import eu.nordtal.season.discordbot.config.Configured;
import eu.nordtal.season.discordbot.config.GuildLanguages;
import eu.nordtal.season.discordbot.roles.GuildRoles;
import eu.nordtal.season.discordbot.roles.Withholding;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.value.Mention;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.requests.ErrorResponse;
import org.jdbi.v3.core.Jdbi;
import org.jspecify.annotations.Nullable;

@Slf4j
public final class AccessRoles {

    /** How far back the expiry sweep looks. It exceeds any restart, and {@code expiry_notice} prevents a second DM. */
    private static final int EXPIRED_LOOKBACK_HOURS = 48;

    private final JDA jda;
    private final AccessSpec config;
    private final GuildLanguages languages;
    private final GuildRoles roles;
    private final Withholding withholding;
    private final AccessDirectory access;
    private final LayoutRenderer layouts;
    private static final String PAGE = "/access";

    private final AdminLog admin;
    private final ReconcileDao dao;

    private final Clock clock;

    public AccessRoles(
            final JDA jda,
            final AccessSpec config,
            final GuildLanguages languages,
            final GuildRoles roles,
            final Withholding withholding,
            final AccessDirectory access,
            final LayoutRenderer layouts,
            final AdminLog admin,
            final Jdbi jdbi,
            final Clock clock) {
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.jda = jda;
        this.config = config;
        this.languages = languages;
        this.roles = roles;
        this.withholding = withholding;
        this.access = access;
        this.layouts = layouts;
        this.admin = admin;
        this.dao = jdbi.onDemand(ReconcileDao.class);
    }

    public boolean hasActiveAccess(final DiscordId discordId) {
        final Instant now = clock.instant();
        return access.grantsOf(discordId).stream().anyMatch(grant -> grant.coversAt(now));
    }

    public Optional<Instant> validUntil(final DiscordId discordId) {
        final Instant now = clock.instant();
        return access.grantsOf(discordId).stream()
                .filter(grant -> grant.revoked() == null && grant.validUntil().isAfter(now))
                .map(AccessGrant::validUntil)
                .max(Instant::compareTo);
    }

    public void applyAccessRole(final DiscordId discordId, final boolean active) {
        final Guild guild = guild();
        final Role role = guild == null ? null : role(guild, GuildRoles.ACCESS);
        if (role == null) {
            return;
        }

        guild.retrieveMemberById(discordId.value())
                .queue(
                        member -> change(
                                member,
                                role,
                                DiscordRole.ACCESS,
                                accessChange(
                                        active,
                                        withholding.withholds(member),
                                        member.getRoles().contains(role))),
                        failure -> log.debug("{} is not a member of the guild, so no access role to set", discordId));
    }

    public void grantDonorRole(final DiscordId discordId) {
        final Guild guild = guild();
        final Role role = guild == null ? null : role(guild, GuildRoles.DONOR);
        if (role == null) {
            return;
        }
        guild.retrieveMemberById(discordId.value())
                .queue(
                        member -> change(
                                member,
                                role,
                                DiscordRole.DONOR,
                                donorChange(
                                        true,
                                        withholding.withholds(member),
                                        member.getRoles().contains(role))),
                        failure -> admin.alert(GuildRoles.notChanged(DiscordRole.DONOR, true, discordId, failure)));
    }

    /**
     * Sets a member's access and donor roles from the database and the lock.
     * A lock that comes or goes calls it, so the roles follow what the member is owed now, not a stored copy.
     */
    public void applyEntitlement(final Member member) {
        final DiscordId discordId = DiscordId.of(member.getId());
        final boolean withheld = withholding.withholds(member);
        final Role accessRole = role(member.getGuild(), GuildRoles.ACCESS);
        if (accessRole != null) {
            change(
                    member,
                    accessRole,
                    DiscordRole.ACCESS,
                    accessChange(
                            hasActiveAccess(discordId),
                            withheld,
                            member.getRoles().contains(accessRole)));
        }
        final Role donorRole = role(member.getGuild(), GuildRoles.DONOR);
        if (donorRole != null) {
            change(
                    member,
                    donorRole,
                    DiscordRole.DONOR,
                    donorChange(
                            access.isDonor(discordId),
                            withheld,
                            member.getRoles().contains(donorRole)));
        }
    }

    public void reconcile() {
        final Guild guild = guild();
        if (guild == null) {
            return;
        }
        final Role role = role(guild, GuildRoles.ACCESS);
        if (role != null) {
            final List<Member> holding = guild.getMembersWithRoles(role);
            final Reconciled reconciled = reconciled(
                    Set.copyOf(dao.withActiveAccess()),
                    holding.stream().map(Member::getId).toList(),
                    id -> guild.getMemberById(id) != null,
                    id -> withheld(guild, id));
            reconciled.take().forEach(id -> changeAccess(guild, id, role, Change.TAKE));
            reconciled.give().forEach(id -> changeAccess(guild, id, role, Change.GIVE));
        }
        final Role donor = role(guild, GuildRoles.DONOR);
        if (donor != null) {
            guild.getMembersWithRoles(donor)
                    .forEach(member -> change(
                            member, donor, DiscordRole.DONOR, donorChange(true, withholding.withholds(member), true)));
        }
    }

    private boolean withheld(final Guild guild, final String memberId) {
        final Member member = guild.getMemberById(memberId);
        return member != null && withholding.withholds(member);
    }

    private void changeAccess(final Guild guild, final String memberId, final Role role, final Change change) {
        final Member member = guild.getMemberById(memberId);
        if (member != null) {
            change(member, role, DiscordRole.ACCESS, change);
        }
    }

    private void change(final Member member, final Role role, final DiscordRole kind, final Change change) {
        if (change == Change.KEEP) {
            return;
        }
        final boolean given = change == Change.GIVE;
        final DiscordId discordId = DiscordId.of(member.getId());
        (given
                        ? member.getGuild().addRoleToMember(member, role)
                        : member.getGuild().removeRoleFromMember(member, role))
                .queue(
                        ok -> log.info(
                                "{} the role {} {} {}",
                                given ? "Gave" : "Took",
                                role.getName(),
                                given ? "to" : "from",
                                discordId),
                        failure -> admin.alert(GuildRoles.notChanged(kind, given, discordId, failure)));
    }

    /**
     * Sends the "runs out soon" and "has run out" DMs, each once per period.
     * The {@code expiry_notice} row is written first, so a crash loses one message instead of repeating all.
     */
    public void sweepExpiryNotices() {
        final int leadHours = config.expiryReminderLeadDays() * 24;

        for (final AccessDeadline deadline : dao.endingWithin(leadHours)) {
            if (!claim(deadline, "SOON")) {
                continue;
            }
            final Locale locale = localeOf(deadline.discordId());
            dm(deadline.discordId(), MESSAGES.dm().expiring(deadline.validUntil(), contributionChannel(locale)));
        }

        for (final AccessDeadline deadline : dao.endedWithin(EXPIRED_LOOKBACK_HOURS)) {
            if (!claim(deadline, "EXPIRED")) {
                continue;
            }
            dm(deadline.discordId(), MESSAGES.dm().expired(contributionChannel(localeOf(deadline.discordId()))));
        }
    }

    public void sweepLinkCodes() {
        final int deleted = dao.deleteExpiredLinkCodes();
        if (deleted > 0) {
            log.debug("Deleted {} expired link code(s)", deleted);
        }
    }

    private boolean claim(final AccessDeadline deadline, final String kind) {
        return dao.noticeOnce(deadline.discordId(), deadline.validUntil().atOffset(ZoneOffset.UTC), kind) == 1;
    }

    public Locale localeOf(final DiscordId discordId) {
        return access.language(discordId);
    }

    public void dm(final DiscordId discordId, final MessageRef message) {
        final LayoutRenderer.Drawn drawn = layouts.reply(localeOf(discordId), message);
        jda.openPrivateChannelById(discordId.value())
                .queue(
                        channel -> channel.sendMessage(drawn.create())
                                .queue(
                                        ok -> log.debug("DMed {}", discordId),
                                        failure -> admin.alert(dmNotDelivered(List.of(
                                                TEXTS.alert().to(Mention.of(discordId)),
                                                TEXTS.alert().words(drawn.text()))))),
                        failure -> admin.alert(dmNotDelivered(List.of(TEXTS.alert()
                                .failedFor(Mention.of(discordId), String.valueOf(failure.getMessage()))))));
    }

    /**
     * Sends a direct message and waits for Discord's answer.
     * An admin waits on it in Steward, so a bounce is theirs to read there and raises no alert.
     *
     * @return whether Discord delivered it
     */
    public boolean dmAndWait(final DiscordId discordId, final String text) {
        try {
            jda.openPrivateChannelById(discordId.value())
                    .flatMap(channel -> channel.sendMessage(text))
                    .complete();
            return true;
        } catch (final RuntimeException bounced) {
            log.info("Discord did not deliver a direct message to {}: {}", discordId, bounced.getMessage());
            return false;
        }
    }

    enum Change {
        GIVE,
        TAKE,
        KEEP
    }

    record Reconciled(Set<String> give, Set<String> take) {}

    static Change accessChange(final boolean entitled, final boolean withheld, final boolean held) {
        final boolean wanted = entitled && !withheld;
        if (wanted == held) {
            return Change.KEEP;
        }
        return wanted ? Change.GIVE : Change.TAKE;
    }

    /** Only the lock takes the donor role. Otherwise a donor keeps it for good. */
    static Change donorChange(final boolean donor, final boolean withheld, final boolean held) {
        if (withheld) {
            return held ? Change.TAKE : Change.KEEP;
        }
        return donor && !held ? Change.GIVE : Change.KEEP;
    }

    /**
     * @param present whether a member of that id is in the guild
     * @param withheld whether the lock withholds the role from the member of that id
     */
    static Reconciled reconciled(
            final Set<String> entitled,
            final Collection<String> holding,
            final Predicate<String> present,
            final Predicate<String> withheld) {
        final Set<String> give = new HashSet<>(entitled);
        holding.forEach(give::remove);
        give.removeIf(id -> !present.test(id) || withheld.test(id));
        final Set<String> take = new HashSet<>();
        for (final String id : holding) {
            if (!entitled.contains(id) || withheld.test(id)) {
                take.add(id);
            }
        }
        return new Reconciled(Set.copyOf(give), Set.copyOf(take));
    }

    /** Returns the role kept for {@code key}, or {@code null} while there is none. {@link GuildRoles} says why. */
    private @Nullable Role role(final Guild guild, final String key) {
        final Role role = roles.role(guild, key).orElse(null);
        if (role == null) {
            log.warn("There is no {} role yet; nothing was changed", key);
        }
        return role;
    }

    private static Alert dmNotDelivered(final List<MessageRef> detail) {
        return new Alert(
                AlertType.BOT, Alert.Level.WARN, "direct message", TEXTS.alert().dm(), detail, PAGE);
    }

    private String contributionChannel(final Locale locale) {
        final String id = languages.forLocale(locale).contributionChannelId();
        return Configured.isSet(id)
                ? "<#" + id + ">"
                : layouts.messages().format(locale, MESSAGES.dm().channel());
    }

    /**
     * Returns the guild member behind a Discord id.
     * It is empty for an unknown member, an unknown account or a non-snowflake id.
     * It throws when JDA cannot see the guild.
     */
    public Optional<Member> member(final DiscordId discordId) {
        final Guild guild = guild();
        if (guild == null) {
            throw new IllegalStateException("guild " + config.guildId() + " is not available to the"
                    + " bot, so guild membership cannot be answered either way");
        }
        try {
            return Optional.ofNullable(
                    guild.retrieveMemberById(discordId.value()).complete());
        } catch (final NumberFormatException notASnowflake) {
            return Optional.empty();
        } catch (final ErrorResponseException failure) {
            if (failure.getErrorResponse() == ErrorResponse.UNKNOWN_MEMBER
                    || failure.getErrorResponse() == ErrorResponse.UNKNOWN_USER) {
                return Optional.empty();
            }
            throw failure;
        }
    }

    private Guild guild() {
        final Guild guild = jda.getGuildById(config.guildId());
        if (guild == null) {
            log.error("Guild {} is not available to the bot", config.guildId());
        }
        return guild;
    }
}
