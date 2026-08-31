package eu.nordtal.s2.discordbot.discord;

import eu.nordtal.s2.discordbot.config.AccessSpec;
import eu.nordtal.s2.common.access.AccessDirectory;
import eu.nordtal.s2.common.access.AccessGrant;
import eu.nordtal.s2.common.message.Locales;
import eu.nordtal.s2.common.message.Messages;

import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.utils.TimeFormat;
import org.jdbi.v3.core.Jdbi;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The two roles, and the messages that go with them.
 *
 * <h2>The access role is owned by the bot</h2>
 * It is a projection of the database and nothing else. {@link #reconcile()} adds it to everyone a
 * grant covers and takes it off everyone else who has it, so handing it out by hand holds only
 * until the next pass - {@code /grant-access} is the supported way. The reconcile reads the
 * <b>member cache</b> rather than calling {@code loadMembers()}: JDA chunks the guild once when the
 * session opens and keeps the cache current from gateway events, so this is a set difference in
 * memory and not a request to Discord every few minutes.
 *
 * <h2>The donor role is never taken away</h2>
 * Not by {@link #reconcile()} and not by anything else here. That is deliberate: it means an admin
 * can hand the donor role out through Discord's own role UI without the bot quietly removing it on
 * the next pass.
 */
@Slf4j
public final class AccessRoles {

    /**
     * How far back the "your access ran out" sweep looks.
     * <p>
     * Longer than any reasonable restart, so a bot that was down at the moment somebody's access
     * expired still tells them when it comes back. Sending twice is prevented by
     * {@code expiry_notice}, not by this window being narrow.
     * </p>
     */
    private static final int EXPIRED_LOOKBACK_HOURS = 48;

    private final JDA jda;
    private final AccessSpec config;
    private final AccessDirectory access;
    private final Messages messages;
    private final AdminLog admin;
    private final ReconcileDao dao;

    public AccessRoles(final JDA jda, final AccessSpec config, final AccessDirectory access,
                       final Messages messages, final AdminLog admin, final Jdbi jdbi) {
        this.jda = jda;
        this.config = config;
        this.access = access;
        this.messages = messages;
        this.admin = admin;
        this.dao = jdbi.onDemand(ReconcileDao.class);
    }

    // ---------------------------------------------------------------- single user

    /** @return whether a non-revoked grant covers this instant */
    public boolean hasActiveAccess(final String discordId) {
        final Instant now = Instant.now();
        return access.grantsOf(discordId).stream().anyMatch(grant -> grant.coversAt(now));
    }

    /** @return when the current run of access ends, if there is one */
    public Optional<Instant> validUntil(final String discordId) {
        final Instant now = Instant.now();
        return access.grantsOf(discordId).stream()
                .filter(grant -> grant.revoked() == null && grant.validUntil().isAfter(now))
                .map(AccessGrant::validUntil)
                .max(Instant::compareTo);
    }

    /** Brings one member's access role in line with the database, right now. */
    public void applyAccessRole(final String discordId, final boolean active) {
        final Guild guild = guild();
        final Role role = guild == null ? null : guild.getRoleById(config.roles().access());
        if (guild == null || role == null) {
            admin.alert("The access role " + config.roles().access() + " does not exist. "
                    + "Nobody's access role is being maintained.");
            return;
        }

        guild.retrieveMemberById(discordId).queue(member -> {
            final boolean has = member.getRoles().contains(role);
            if (active && !has) {
                guild.addRoleToMember(member, role).queue(
                        ok -> log.info("Gave the access role to {}", discordId),
                        failure -> admin.alert("Could not give the access role to <@" + discordId + ">: "
                                + failure.getMessage()));
            } else if (!active && has) {
                guild.removeRoleFromMember(member, role).queue(
                        ok -> log.info("Took the access role from {}", discordId),
                        failure -> admin.alert("Could not take the access role from <@" + discordId + ">: "
                                + failure.getMessage()));
            }
        }, failure -> log.debug("{} is not a member of the guild, so no access role to set", discordId));
    }

    /** Grants the permanent donor role. Never has a counterpart that removes it. */
    public void grantDonorRole(final String discordId) {
        final Guild guild = guild();
        final Role role = guild == null ? null : guild.getRoleById(config.roles().donor());
        if (guild == null || role == null) {
            admin.alert("The donor role " + config.roles().donor() + " does not exist, so <@" + discordId
                    + "> did not get it. The donor flag in the database is set either way.");
            return;
        }
        guild.addRoleToMember(net.dv8tion.jda.api.entities.UserSnowflake.fromId(discordId), role).queue(
                ok -> log.info("Gave the donor role to {}", discordId),
                failure -> admin.alert("Could not give the donor role to <@" + discordId + ">: "
                        + failure.getMessage()));
    }

    // ---------------------------------------------------------------- the sweeps

    /**
     * One pass over "who holds the role" against "who holds a grant".
     * <p>
     * Both sides are bounded by the thing being reconciled: the members who have the role come out
     * of the member cache, the users who have access come out of one indexed query. Season 1's
     * equivalent walked every member of every guild every ten seconds and asked the database about
     * each one.
     * </p>
     */
    public void reconcile() {
        final Guild guild = guild();
        if (guild == null) {
            return;
        }
        final Role role = guild.getRoleById(config.roles().access());
        if (role == null) {
            admin.alert("The access role " + config.roles().access() + " does not exist. "
                    + "The reconcile is doing nothing.");
            return;
        }

        final Set<String> shouldHave = new HashSet<>(dao.withActiveAccess());
        final List<Member> hasRole = guild.getMembersWithRoles(role);

        for (final Member member : hasRole) {
            if (!shouldHave.remove(member.getId())) {
                guild.removeRoleFromMember(member, role).queue(
                        ok -> log.info("Reconcile: took the access role from {}", member.getId()),
                        failure -> admin.alert("Reconcile could not take the access role from "
                                + member.getAsMention() + ": " + failure.getMessage()));
            }
        }

        // Whatever is left had a grant and no role.
        for (final String discordId : shouldHave) {
            final Member member = guild.getMemberById(discordId);
            if (member == null) {
                // Has paid and is not in the guild. Not an error - the period keeps running down
                // and the role is waiting for them if they come back.
                continue;
            }
            guild.addRoleToMember(member, role).queue(
                    ok -> log.info("Reconcile: gave the access role to {}", discordId),
                    failure -> admin.alert("Reconcile could not give the access role to <@" + discordId
                            + ">: " + failure.getMessage()));
        }
    }

    /**
     * Sends the "runs out soon" and "has run out" DMs, each exactly once per period.
     * <p>
     * The "exactly once" is a row in {@code expiry_notice} keyed by the deadline, inserted before
     * the message is sent. Sending and then recording would re-send everything after a crash
     * between the two; recording and then failing to send loses one message and tells an admin.
     * </p>
     */
    public void sweepExpiryNotices() {
        final int leadHours = config.expiryReminderLeadDays() * 24;

        for (final AccessDeadline deadline : dao.endingWithin(leadHours)) {
            if (!claim(deadline, "SOON")) {
                continue;
            }
            final Locale locale = localeOf(deadline.discordId());
            final long days = Math.max(1,
                    Duration.between(Instant.now(), deadline.validUntil()).toDays());
            dm(deadline.discordId(), messages.format(locale, "dm.expiring",
                    "until", timestamp(deadline.validUntil()), "days", days));
        }

        for (final AccessDeadline deadline : dao.endedWithin(EXPIRED_LOOKBACK_HOURS)) {
            if (!claim(deadline, "EXPIRED")) {
                continue;
            }
            dm(deadline.discordId(), messages.get(localeOf(deadline.discordId()), "dm.expired"));
        }
    }

    /** Deletes link codes that have run out. Stage C issues them; the sweep is the bot's. */
    public void sweepLinkCodes() {
        final int deleted = dao.deleteExpiredLinkCodes();
        if (deleted > 0) {
            log.debug("Deleted {} expired link code(s)", deleted);
        }
    }

    private boolean claim(final AccessDeadline deadline, final String kind) {
        return dao.noticeOnce(deadline.discordId(),
                deadline.validUntil().atOffset(ZoneOffset.UTC), kind) == 1;
    }

    // ---------------------------------------------------------------- messages

    /** The language this Discord account chose, English when it never did. */
    public Locale localeOf(final String discordId) {
        return Locales.parse(dao.localeOf(discordId).orElse(null));
    }

    /**
     * Sends a direct message, and tells the admin channel when it bounces.
     * <p>
     * A blocked DM is the normal case, not an exception: plenty of people have DMs from server
     * members turned off. It is reported rather than logged and forgotten because the user has
     * paid for something and has just been told nothing.
     * </p>
     */
    public void dm(final String discordId, final String text) {
        jda.openPrivateChannelById(discordId).queue(
                channel -> channel.sendMessage(text).queue(
                        ok -> log.debug("DMed {}", discordId),
                        failure -> admin.alert("Could not DM <@" + discordId + "> - they probably have "
                                + "direct messages closed. The message was: " + text)),
                failure -> admin.alert("Could not open a DM channel with <@" + discordId + ">: "
                        + failure.getMessage()));
    }

    /** A Discord timestamp, so every reader sees the moment in their own time zone. */
    public static String timestamp(final Instant instant) {
        return TimeFormat.DATE_TIME_SHORT.format(OffsetDateTime.ofInstant(instant, ZoneOffset.UTC));
    }

    private Guild guild() {
        final Guild guild = jda.getGuildById(config.guildId());
        if (guild == null) {
            log.error("Guild {} is not available to the bot", config.guildId());
        }
        return guild;
    }
}
