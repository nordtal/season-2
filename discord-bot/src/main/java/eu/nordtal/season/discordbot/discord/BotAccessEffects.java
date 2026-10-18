package eu.nordtal.season.discordbot.discord;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.database.JournalDetail;
import eu.nordtal.season.database.access.AccessDirectory;
import eu.nordtal.season.database.access.AccessGrant;
import eu.nordtal.season.database.access.AccessSource;
import eu.nordtal.season.database.audit.AuditLine;
import eu.nordtal.season.database.audit.JournalAction;
import eu.nordtal.season.discordbot.AdminLog;
import eu.nordtal.season.discordbot.DocumentRenderer;
import eu.nordtal.season.discordbot.Replies;
import eu.nordtal.season.discordbot.access.SeasonStart;
import eu.nordtal.season.discordbot.access.discord.AccessRoles;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Runs the {@link AccessChanges} on the database row, the Discord role, a direct message and the audit entry. */
public final class BotAccessEffects implements AccessChanges {

    private final AccessDirectory access;
    private final AccessRoles roles;
    private final AdminLog admin;
    private final SeasonStart seasonStart;

    public BotAccessEffects(
            final AccessDirectory access,
            final AccessRoles roles,
            final AdminLog admin,
            final SeasonStart seasonStart) {
        this.access = access;
        this.roles = roles;
        this.admin = admin;
        this.seasonStart = seasonStart;
    }

    @Override
    public Instant grant(final DiscordId discordId, final int days, final Actor by) {
        final AccessGrant granted = access.grantAccess(discordId, days, AccessSource.ADMIN, null);
        seasonStart.warnIfUnanchored(discordId, granted.validFrom());
        roles.applyAccessRole(discordId, true);
        roles.dm(
                discordId,
                new DocumentRenderer.Reply<>(Replies.DM_GRANTED_ADMIN, new Replies.Given(days, granted.validUntil())));

        admin.record(AuditLine.about(
                JournalAction.GRANT_ACCESS,
                by,
                discordId,
                new JournalDetail.AccessGranted(days, granted.validUntil())));
        return granted.validUntil();
    }

    @Override
    public int revoke(final DiscordId discordId, final Actor by) {
        final int revoked = access.revokeAccess(discordId);
        roles.applyAccessRole(discordId, false);
        if (revoked > 0) {
            roles.dm(discordId, DocumentRenderer.Reply.of(Replies.DM_REVOKED));
        }

        admin.record(
                AuditLine.about(JournalAction.REVOKE_ACCESS, by, discordId, new JournalDetail.AccessRevoked(revoked)));
        return revoked;
    }

    @Override
    public boolean unlink(final DiscordId discordId, final Actor by) {
        // Read before the unlink, which deletes the UUID.
        final Optional<UUID> linked = access.linkedMinecraftAccount(discordId);
        if (!access.unlink(discordId)) {
            return false;
        }
        admin.record(new AuditLine(
                JournalAction.UNLINK, by, discordId, linked.orElse(null), new JournalDetail.Unlinked(false)));
        return true;
    }

    /** Sets the play time without a direct message. */
    @Override
    public void setPlaytime(final DiscordId discordId, final long seconds, final Actor by) {
        access.setPlaytimeSeconds(discordId, seconds);
        admin.record(AuditLine.about(
                JournalAction.SET_PLAYTIME, by, discordId, new JournalDetail.PlaytimeSet(Duration.ofSeconds(seconds))));
    }
}
