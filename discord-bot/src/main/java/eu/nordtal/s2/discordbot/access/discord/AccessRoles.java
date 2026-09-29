package eu.nordtal.s2.discordbot.access.discord;

import static eu.nordtal.s2.discordbot.AccessMessages.MESSAGES;

import eu.nordtal.s2.common.access.AccessDirectory;
import eu.nordtal.s2.common.access.AccessGrant;
import eu.nordtal.s2.common.message.Locales;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.discordbot.AdminLog;
import eu.nordtal.s2.discordbot.config.AccessSpec;
import eu.nordtal.s2.discordbot.config.Configured;
import eu.nordtal.s2.discordbot.config.Languages;
import java.time.Instant;
import java.time.OffsetDateTime;
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
import net.dv8tion.jda.api.utils.TimeFormat;
import org.jdbi.v3.core.Jdbi;

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
    private final AccessDirectory access;
    private final Messages messages;
    private final AdminLog admin;
    private final ReconcileDao dao;

    public AccessRoles(
            final JDA jda,
            final AccessSpec config,
            final AccessDirectory access,
            final Messages messages,
            final AdminLog admin,
            final Jdbi jdbi) {
        this.jda = jda;
        this.config = config;
        this.access = access;
        this.messages = messages;
        this.admin = admin;
        this.dao = jdbi.onDemand(ReconcileDao.class);
    }

    /** Returns whether a non-revoked grant covers this instant. */
    public boolean hasActiveAccess(final String discordId) {
        final Instant now = Instant.now();
        return access.grantsOf(discordId).stream().anyMatch(grant -> grant.coversAt(now));
    }

    /** Returns when the current run of access ends, if there is one. */
    public Optional<Instant> validUntil(final String discordId) {
        final Instant now = Instant.now();
        return access.grantsOf(discordId).stream()
                .filter(grant -> grant.revoked() == null && grant.validUntil().isAfter(now))
                .map(AccessGrant::validUntil)
                .max(Instant::compareTo);
    }

    /** Brings one member's access role in line with the database, right now. */
    public void applyAccessRole(final String discordId, final boolean active) {
        // No access role configured is not a failure: the grant stays in the database, which the proxy reads.
        if (!Configured.isSet(config.roles().access())) {
            return;
        }
        final Guild guild = guild();
        final Role role =
                guild == null ? null : guild.getRoleById(config.roles().access());
        if (guild == null || role == null) {
            admin.alert(
                    "⚠️ Access role missing",
                    "`" + config.roles().access() + "` does not exist. Nobody's role is kept.");
            return;
        }

        guild.retrieveMemberById(discordId)
                .queue(
                        member -> {
                            final boolean has = member.getRoles().contains(role);
                            if (active && !has) {
                                guild.addRoleToMember(member, role)
                                        .queue(
                                                ok -> log.info("Gave the access role to {}", discordId),
                                                failure -> admin.alert(
                                                        "⚠️ Access role not given",
                                                        "<@" + discordId + "> " + failure.getMessage()));
                            } else if (!active && has) {
                                guild.removeRoleFromMember(member, role)
                                        .queue(
                                                ok -> log.info("Took the access role from {}", discordId),
                                                failure -> admin.alert(
                                                        "⚠️ Access role not taken",
                                                        "<@" + discordId + "> " + failure.getMessage()));
                            }
                        },
                        failure -> log.debug("{} is not a member of the guild, so no access role to set", discordId));
    }

    /** Grants the permanent donor role; nothing removes it. */
    public void grantDonorRole(final String discordId) {
        // The donor flag in the database is already set by the caller; the role is the decoration.
        if (!Configured.isSet(config.roles().donor())) {
            return;
        }
        final Guild guild = guild();
        final Role role =
                guild == null ? null : guild.getRoleById(config.roles().donor());
        if (guild == null || role == null) {
            admin.alert(
                    "⚠️ Donor role missing",
                    "`" + config.roles().donor() + "` does not exist, so <@" + discordId
                            + "> did not get it. The donor flag is set either way.");
            return;
        }
        guild.addRoleToMember(net.dv8tion.jda.api.entities.UserSnowflake.fromId(discordId), role)
                .queue(
                        ok -> log.info("Gave the donor role to {}", discordId),
                        failure ->
                                admin.alert("⚠️ Donor role not given", "<@" + discordId + "> " + failure.getMessage()));
    }

    /** Adds the access role to everyone a grant covers and removes it from everyone else. */
    public void reconcile() {
        if (!Configured.isSet(config.roles().access())) {
            return;
        }
        final Guild guild = guild();
        if (guild == null) {
            return;
        }
        final Role role = guild.getRoleById(config.roles().access());
        if (role == null) {
            admin.alert(
                    "⚠️ Access role missing",
                    "`" + config.roles().access() + "` does not exist. The reconcile does nothing.");
            return;
        }

        final Set<String> shouldHave = new HashSet<>(dao.withActiveAccess());
        final List<Member> hasRole = guild.getMembersWithRoles(role);

        for (final Member member : hasRole) {
            if (!shouldHave.remove(member.getId())) {
                guild.removeRoleFromMember(member, role)
                        .queue(
                                ok -> log.info("Reconcile: took the access role from {}", member.getId()),
                                failure -> admin.alert(
                                        "⚠️ Access role not taken",
                                        member.getAsMention() + " " + failure.getMessage()));
            }
        }

        // Whatever is left had a grant and no role.
        for (final String discordId : shouldHave) {
            final Member member = guild.getMemberById(discordId);
            if (member == null) {
                // Paid and not in the guild: not an error, the role waits for a return.
                continue;
            }
            guild.addRoleToMember(member, role)
                    .queue(
                            ok -> log.info("Reconcile: gave the access role to {}", discordId),
                            failure -> admin.alert(
                                    "⚠️ Access role not given", "<@" + discordId + "> " + failure.getMessage()));
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
                            locale,
                            MESSAGES.dm()
                                    .expiring(
                                            TimeFormat.RELATIVE.format(deadline.validUntil()),
                                            timestamp(deadline.validUntil()),
                                            contributionChannel(locale))));
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
    public Locale localeOf(final String discordId) {
        return Locales.parse(dao.localeOf(discordId).orElse(null));
    }

    /** Sends a direct message, and tells the admin channel when it bounces. */
    public void dm(final String discordId, final String text) {
        jda.openPrivateChannelById(discordId)
                .queue(
                        channel -> channel.sendMessage(text)
                                .queue(
                                        ok -> log.debug("DMed {}", discordId),
                                        // Usually closed direct messages.
                                        failure -> admin.alert("✉️ DM not delivered", "<@" + discordId + ">\n" + text)),
                        failure -> admin.alert("✉️ DM not delivered", "<@" + discordId + "> " + failure.getMessage()));
    }

    /** Returns the contribution channel of a language as a mention, or its name when none is configured. */
    private String contributionChannel(final Locale locale) {
        final String id = Languages.of(config).forLocale(locale).contributionChannelId();
        return Configured.isSet(id)
                ? "<#" + id + ">"
                : messages.format(locale, MESSAGES.dm().channel());
    }

    /** Returns a Discord timestamp, shown in each reader's own time zone. */
    public static String timestamp(final Instant instant) {
        return TimeFormat.DATE_TIME_SHORT.format(OffsetDateTime.ofInstant(instant, ZoneOffset.UTC));
    }

    /**
     * Returns the guild member behind a Discord id, or empty when there is none.
     *
     * An unknown member, unknown account or non-snowflake id is empty; a guild JDA cannot see throws.
     */
    public Optional<Member> member(final String discordId) {
        final Guild guild = guild();
        if (guild == null) {
            // Thrown and not empty. Empty means "no such member", and a guild JDA cannot see is no evidence of that.
            throw new IllegalStateException("guild " + config.guildId() + " is not available to the"
                    + " bot, so guild membership cannot be answered either way");
        }
        try {
            return Optional.ofNullable(guild.retrieveMemberById(discordId).complete());
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
