package eu.nordtal.s2.discordbot.discord;

import static eu.nordtal.s2.discordbot.AccessMessages.MESSAGES;

import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.database.access.AccessDirectory;
import eu.nordtal.s2.database.access.AccessGrant;
import eu.nordtal.s2.database.access.AccessSource;
import eu.nordtal.s2.database.access.PlaytimeWording;
import eu.nordtal.s2.database.audit.AuditLine;
import eu.nordtal.s2.discordbot.AdminLog;
import eu.nordtal.s2.discordbot.access.SeasonStart;
import eu.nordtal.s2.discordbot.access.discord.AccessRoles;
import eu.nordtal.s2.messages.Messages;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** {@link AccessChanges} against this bot: the row, the Discord role, the direct message and the audit entry. */
public final class BotAccessEffects implements AccessChanges {

    private final AccessDirectory access;
    private final AccessRoles roles;
    private final AdminLog admin;
    private final SeasonStart seasonStart;
    private final Messages messages;

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
            final Messages messages) {
        this.access = access;
        this.roles = roles;
        this.admin = admin;
        this.seasonStart = seasonStart;
        this.messages = messages;
    }

    /** Grants access: the row, the role, the direct message and the admin channel line. */
    @Override
    public Instant grant(final DiscordId discordId, final int days, final Actor by) {
        final AccessGrant granted = access.grantAccess(discordId, days, AccessSource.ADMIN, null);
        seasonStart.warnIfUnanchored(discordId, granted.validFrom());
        roles.applyAccessRole(discordId, true);
        roles.dm(
                discordId,
                messages.format(
                        roles.localeOf(discordId),
                        MESSAGES.dm().grantedSection().admin(days, AccessRoles.timestamp(granted.validUntil()))));

        admin.record(new AuditLine(
                "GRANT_ACCESS",
                by.filed(),
                discordId,
                by.minecraftUuid(),
                Map.of("days", days, "until", granted.validUntil())));
        admin.note(
                "🎟️ Access granted",
                by.mention() + " → <@" + discordId + "> " + days + " days, until "
                        + AccessRoles.timestamp(granted.validUntil()));
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

        admin.record(
                new AuditLine("REVOKE_ACCESS", by.filed(), discordId, by.minecraftUuid(), Map.of("grants", revoked)));
        admin.note("🚫 Access revoked", by.mention() + " → <@" + discordId + ">, " + revoked + " grants");
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
        admin.record(new AuditLine("UNLINK", by.filed(), discordId, linked.orElse(null), Map.of("selfService", false)));
        admin.note(
                "✂️ Unlinked",
                by.mention() + " → <@" + discordId + "> `"
                        + linked.map(UUID::toString).orElse("?") + "`");
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
        admin.record(
                new AuditLine("SET_PLAYTIME", by.filed(), discordId, by.minecraftUuid(), Map.of("seconds", seconds)));
        admin.note("⏱️ Play time set", by.mention() + " → <@" + discordId + "> " + PlaytimeWording.of(seconds));
    }
}
