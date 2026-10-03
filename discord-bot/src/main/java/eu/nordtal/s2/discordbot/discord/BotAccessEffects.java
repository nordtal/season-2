package eu.nordtal.s2.discordbot.discord;

import static eu.nordtal.s2.database.AdminTexts.TEXTS;
import static eu.nordtal.s2.discordbot.AccessMessages.MESSAGES;

import eu.nordtal.s2.common.id.Actor;
import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.database.access.AccessDirectory;
import eu.nordtal.s2.database.access.AccessGrant;
import eu.nordtal.s2.database.access.AccessSource;
import eu.nordtal.s2.database.audit.AuditLine;
import eu.nordtal.s2.database.audit.JournalAction;
import eu.nordtal.s2.discordbot.AdminLog;
import eu.nordtal.s2.discordbot.DiscordRenderer;
import eu.nordtal.s2.discordbot.access.SeasonStart;
import eu.nordtal.s2.discordbot.access.discord.AccessRoles;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** {@link AccessChanges} against this bot: the row, the Discord role, the direct message and the audit entry. */
public final class BotAccessEffects implements AccessChanges {

    private final AccessDirectory access;
    private final AccessRoles roles;
    private final AdminLog admin;
    private final SeasonStart seasonStart;
    private final DiscordRenderer messages;

    /**
     * Creates the effects.
     *
     * @param messages this bot's bundle, with the overrides the database holds
     */
    public BotAccessEffects(
            final AccessDirectory access,
            final AccessRoles roles,
            final AdminLog admin,
            final SeasonStart seasonStart,
            final DiscordRenderer messages) {
        this.access = access;
        this.roles = roles;
        this.admin = admin;
        this.seasonStart = seasonStart;
        this.messages = messages;
    }

    /** Grants access: the row, the role, the direct message and the journal line, which the admin channel shows. */
    @Override
    public Instant grant(final DiscordId discordId, final int days, final Actor by) {
        final AccessGrant granted = access.grantAccess(discordId, days, AccessSource.ADMIN, null);
        seasonStart.warnIfUnanchored(discordId, granted.validFrom());
        roles.applyAccessRole(discordId, true);
        roles.dm(
                discordId,
                messages.format(
                        roles.localeOf(discordId),
                        MESSAGES.dm().grantedSection().admin(days, granted.validUntil())));

        admin.record(AuditLine.about(
                JournalAction.GRANT_ACCESS, by, discordId, TEXTS.journal().grantAccess(days, granted.validUntil())));
        return granted.validUntil();
    }

    /** Returns how many grants were revoked, zero included. */
    @Override
    public int revoke(final DiscordId discordId, final Actor by) {
        final int revoked = access.revokeAccess(discordId);
        roles.applyAccessRole(discordId, false);
        if (revoked > 0) {
            roles.dm(
                    discordId,
                    messages.format(roles.localeOf(discordId), MESSAGES.dm().revoked()));
        }

        admin.record(AuditLine.about(
                JournalAction.REVOKE_ACCESS, by, discordId, TEXTS.journal().revokeAccess(revoked)));
        return revoked;
    }

    /** Returns whether there was a link to break. */
    @Override
    public boolean unlink(final DiscordId discordId, final Actor by) {
        // Read before the unlink: afterwards only the audit entry keeps the UUID.
        final Optional<UUID> linked = access.linkedMinecraftAccount(discordId);
        if (!access.unlink(discordId)) {
            return false;
        }
        admin.record(new AuditLine(
                JournalAction.UNLINK,
                by,
                discordId,
                linked.orElse(null),
                TEXTS.journal().unlink(false)));
        return true;
    }

    /**
     * Writes somebody's total play time, without a direct message.
     *
     * @param seconds the new total, which is what the column holds
     */
    @Override
    public void setPlaytime(final DiscordId discordId, final long seconds, final Actor by) {
        access.setPlaytimeSeconds(discordId, seconds);
        admin.record(AuditLine.about(
                JournalAction.SET_PLAYTIME, by, discordId, TEXTS.journal().setPlaytime(Duration.ofSeconds(seconds))));
    }
}
