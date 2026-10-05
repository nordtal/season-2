package eu.nordtal.s2.discordbot.access.discord;

import static eu.nordtal.s2.database.AdminTexts.TEXTS;
import static eu.nordtal.s2.discordbot.AccessMessages.MESSAGES;

import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.database.access.AccessDirectory;
import eu.nordtal.s2.database.access.AccessGrant;
import eu.nordtal.s2.database.alert.Alert;
import eu.nordtal.s2.database.alert.AlertType;
import eu.nordtal.s2.database.alert.DiscordRole;
import eu.nordtal.s2.discordbot.AdminLog;
import eu.nordtal.s2.discordbot.DiscordRenderer;
import eu.nordtal.s2.discordbot.config.AccessSpec;
import eu.nordtal.s2.discordbot.config.Configured;
import eu.nordtal.s2.discordbot.config.Languages;
import eu.nordtal.s2.discordbot.roles.GuildRoles;
import eu.nordtal.s2.discordbot.roles.Withholding;
import eu.nordtal.s2.messages.MessageRef;
import eu.nordtal.s2.messages.value.Mention;
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

/**
 * The access and donor roles, and the messages that go with them.
 *
 * The access role mirrors the database, so {@link #reconcile()} undoes manual changes; the lock withholds both roles.
 */
@Slf4j
public final class AccessRoles {

    /** How far back the expiry sweep looks, longer than any restart; {@code expiry_notice} prevents a second DM. */
    private static final int EXPIRED_LOOKBACK_HOURS = 48;

    private final JDA jda;
    private final AccessSpec config;
    private final GuildRoles roles;
    private final Withholding withholding;
    private final AccessDirectory access;
    private final DiscordRenderer messages;
    /** The page in Steward an alert about a member's access opens. */
    private static final String PAGE = "/access";

    private final AdminLog admin;
    private final ReconcileDao dao;

    private final Clock clock;

    public AccessRoles(
            final JDA jda,
            final AccessSpec config,
            final GuildRoles roles,
            final Withholding withholding,
            final AccessDirectory access,
            final DiscordRenderer messages,
            final AdminLog admin,
            final Jdbi jdbi,
            final Clock clock) {
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.jda = jda;
        this.config = config;
        this.roles = roles;
        this.withholding = withholding;
        this.access = access;
        this.messages = messages;
        this.admin = admin;
        this.dao = jdbi.onDemand(ReconcileDao.class);
    }

    /** Returns whether a non-revoked grant covers this instant. */
    public boolean hasActiveAccess(final DiscordId discordId) {
        final Instant now = clock.instant();
        return access.grantsOf(discordId).stream().anyMatch(grant -> grant.coversAt(now));
    }

    /** Returns when the current run of access ends, if there is one. */
    public Optional<Instant> validUntil(final DiscordId discordId) {
        final Instant now = clock.instant();
        return access.grantsOf(discordId).stream()
                .filter(grant -> grant.revoked() == null && grant.validUntil().isAfter(now))
                .map(AccessGrant::validUntil)
                .max(Instant::compareTo);
    }

    /** Brings one member's access role in line with the database and the lock, right now. */
    public void applyAccessRole(final DiscordId discordId, final boolean active) {
        final Guild guild = guild();
        final Role role = guild == null ? null : role(guild, GuildRoles.ACCESS);
        if (role == null) {
            // GuildRoles has said why; the grant stays in the database, which the proxy reads.
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

    /** Gives the donor role unless the lock withholds it; nothing else ever takes it. */
    public void grantDonorRole(final DiscordId discordId) {
        // The donor flag in the database is already set by the caller; the role is the decoration.
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
     * Brings a member's access and donor roles in line with the database and the lock.
     *
     * The lock coming or going calls it, so the roles come back from what the member is owed now, not from a copy.
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

    /** Gives the access role to everyone a grant covers and the lock leaves alone, takes it from everyone else. */
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
                    // Paid and not in the guild: not an error, the role waits for a return.
                    id -> guild.getMemberById(id) != null,
                    id -> withheld(guild, id));
            reconciled.take().forEach(id -> changeAccess(guild, id, role, Change.TAKE));
            reconciled.give().forEach(id -> changeAccess(guild, id, role, Change.GIVE));
        }
        final Role donor = role(guild, GuildRoles.DONOR);
        if (donor != null) {
            // Every holder is owed it as far as the role goes, so only the lock decides.
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

    /** Gives or takes one role of one member as decided, and raises an alert when Discord refuses. */
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
     * Sends the "runs out soon" and "has run out" DMs, each exactly once per period.
     *
     * The {@code expiry_notice} row is written first, so a crash loses one message rather than repeating all.
     */
    public void sweepExpiryNotices() {
        final int leadHours = config.expiryReminderLeadDays() * 24;

        for (final AccessDeadline deadline : dao.endingWithin(leadHours)) {
            if (!claim(deadline, "SOON")) {
                continue;
            }
            final Locale locale = localeOf(deadline.discordId());
            dm(
                    deadline.discordId(),
                    messages.format(
                            locale, MESSAGES.dm().expiring(deadline.validUntil(), contributionChannel(locale))));
        }

        for (final AccessDeadline deadline : dao.endedWithin(EXPIRED_LOOKBACK_HOURS)) {
            if (!claim(deadline, "EXPIRED")) {
                continue;
            }
            dm(
                    deadline.discordId(),
                    messages.format(
                            localeOf(deadline.discordId()),
                            MESSAGES.dm().expired(contributionChannel(localeOf(deadline.discordId())))));
        }
    }

    /** Deletes link codes that have run out. */
    public void sweepLinkCodes() {
        final int deleted = dao.deleteExpiredLinkCodes();
        if (deleted > 0) {
            log.debug("Deleted {} expired link code(s)", deleted);
        }
    }

    private boolean claim(final AccessDeadline deadline, final String kind) {
        return dao.noticeOnce(deadline.discordId(), deadline.validUntil().atOffset(ZoneOffset.UTC), kind) == 1;
    }

    /** Returns the language this Discord account chose, or English. */
    public Locale localeOf(final DiscordId discordId) {
        return access.language(discordId);
    }

    /** Sends a direct message, and raises an alert when it bounces. */
    public void dm(final DiscordId discordId, final String text) {
        jda.openPrivateChannelById(discordId.value())
                .queue(
                        channel -> channel.sendMessage(text)
                                .queue(
                                        ok -> log.debug("DMed {}", discordId),
                                        // Usually closed direct messages.
                                        failure -> admin.alert(dmNotDelivered(List.of(
                                                TEXTS.alert().to(Mention.of(discordId)),
                                                TEXTS.alert().words(text))))),
                        failure -> admin.alert(dmNotDelivered(List.of(TEXTS.alert()
                                .failedFor(Mention.of(discordId), String.valueOf(failure.getMessage()))))));
    }

    /**
     * Sends a direct message and waits for Discord's answer.
     * An admin waits on it in Steward, so a bounce is theirs to read there, not an alert.
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

    /** What one role does for one member: given, taken, or left as it is. */
    enum Change {
        GIVE,
        TAKE,
        KEEP
    }

    /**
     * Who the reconcile gives the access role and who it takes it from.
     *
     * @param give the ids of members who are given it
     * @param take the ids of members who lose it
     */
    record Reconciled(Set<String> give, Set<String> take) {}

    /** Decides the access role: held while a grant covers the member and the lock does not withhold it. */
    static Change accessChange(final boolean entitled, final boolean withheld, final boolean held) {
        final boolean wanted = entitled && !withheld;
        if (wanted == held) {
            return Change.KEEP;
        }
        return wanted ? Change.GIVE : Change.TAKE;
    }

    /** Decides the donor role, which only the lock ever takes: otherwise a donor keeps it for good. */
    static Change donorChange(final boolean donor, final boolean withheld, final boolean held) {
        if (withheld) {
            return held ? Change.TAKE : Change.KEEP;
        }
        return donor && !held ? Change.GIVE : Change.KEEP;
    }

    /**
     * Decides the reconcile of the access role over the whole guild.
     *
     * @param entitled the ids a grant covers now
     * @param holding the ids of the members who hold the role
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

    /** Returns the role kept for {@code key}, or {@code null} while there is none, which {@link GuildRoles} says. */
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

    /** Returns the contribution channel of a language as a mention, or its name when none is configured. */
    private String contributionChannel(final Locale locale) {
        final String id = Languages.of(config).forLocale(locale).contributionChannelId();
        return Configured.isSet(id)
                ? "<#" + id + ">"
                : messages.format(locale, MESSAGES.dm().channel());
    }

    /**
     * Returns the guild member behind a Discord id, or empty when there is none.
     *
     * An unknown member, unknown account or non-snowflake id is empty; a guild JDA cannot see throws.
     */
    public Optional<Member> member(final DiscordId discordId) {
        final Guild guild = guild();
        if (guild == null) {
            // Thrown and not empty. Empty means "no such member", and a guild JDA cannot see is no evidence of that.
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
