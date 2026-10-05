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
import eu.nordtal.s2.messages.MessageRef;
import eu.nordtal.s2.messages.value.Mention;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
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
 * The access role mirrors the database, so {@link #reconcile()} undoes manual changes; the donor role is never removed.
 */
@Slf4j
public final class AccessRoles {

    /** How far back the expiry sweep looks, longer than any restart; {@code expiry_notice} prevents a second DM. */
    private static final int EXPIRED_LOOKBACK_HOURS = 48;

    private final JDA jda;
    private final AccessSpec config;
    private final GuildRoles roles;
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
            final AccessDirectory access,
            final DiscordRenderer messages,
            final AdminLog admin,
            final Jdbi jdbi,
            final Clock clock) {
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.jda = jda;
        this.config = config;
        this.roles = roles;
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

    /** Brings one member's access role in line with the database, right now. */
    public void applyAccessRole(final DiscordId discordId, final boolean active) {
        final Guild guild = guild();
        final Role role = guild == null ? null : role(guild, GuildRoles.ACCESS);
        if (role == null) {
            // GuildRoles has said why; the grant stays in the database, which the proxy reads.
            return;
        }

        guild.retrieveMemberById(discordId.value())
                .queue(
                        member -> {
                            final boolean has = member.getRoles().contains(role);
                            if (active && !has) {
                                guild.addRoleToMember(member, role)
                                        .queue(
                                                ok -> log.info("Gave the access role to {}", discordId),
                                                failure -> admin.alert(GuildRoles.notChanged(
                                                        DiscordRole.ACCESS, true, discordId, failure)));
                            } else if (!active && has) {
                                guild.removeRoleFromMember(member, role)
                                        .queue(
                                                ok -> log.info("Took the access role from {}", discordId),
                                                failure -> admin.alert(GuildRoles.notChanged(
                                                        DiscordRole.ACCESS, false, discordId, failure)));
                            }
                        },
                        failure -> log.debug("{} is not a member of the guild, so no access role to set", discordId));
    }

    /** Grants the permanent donor role; nothing removes it. */
    public void grantDonorRole(final DiscordId discordId) {
        // The donor flag in the database is already set by the caller; the role is the decoration.
        final Guild guild = guild();
        final Role role = guild == null ? null : role(guild, GuildRoles.DONOR);
        if (role == null) {
            return;
        }
        guild.addRoleToMember(net.dv8tion.jda.api.entities.UserSnowflake.fromId(discordId.value()), role)
                .queue(
                        ok -> log.info("Gave the donor role to {}", discordId),
                        failure -> admin.alert(GuildRoles.notChanged(DiscordRole.DONOR, true, discordId, failure)));
    }

    /** Adds the access role to everyone a grant covers and removes it from everyone else. */
    public void reconcile() {
        final Guild guild = guild();
        final Role role = guild == null ? null : role(guild, GuildRoles.ACCESS);
        if (role == null) {
            return;
        }

        final Set<String> shouldHave = new HashSet<>(dao.withActiveAccess());
        final List<Member> hasRole = guild.getMembersWithRoles(role);

        for (final Member member : hasRole) {
            if (!shouldHave.remove(member.getId())) {
                guild.removeRoleFromMember(member, role)
                        .queue(
                                ok -> log.info("Reconcile: took the access role from {}", member.getId()),
                                failure -> admin.alert(GuildRoles.notChanged(
                                        DiscordRole.ACCESS, false, DiscordId.of(member.getId()), failure)));
            }
        }

        // Whatever is left had a grant and no role.
        for (final DiscordId discordId : shouldHave.stream().map(DiscordId::of).toList()) {
            final Member member = guild.getMemberById(discordId.value());
            if (member == null) {
                // Paid and not in the guild: not an error, the role waits for a return.
                continue;
            }
            guild.addRoleToMember(member, role)
                    .queue(
                            ok -> log.info("Reconcile: gave the access role to {}", discordId),
                            failure ->
                                    admin.alert(GuildRoles.notChanged(DiscordRole.ACCESS, true, discordId, failure)));
        }
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
