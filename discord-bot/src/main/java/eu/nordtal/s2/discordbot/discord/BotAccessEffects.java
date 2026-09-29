package eu.nordtal.s2.discordbot.discord;

import static eu.nordtal.s2.discordbot.AccessMessages.MESSAGES;

import eu.nordtal.s2.common.access.AccessDirectory;
import eu.nordtal.s2.common.access.AccessGrant;
import eu.nordtal.s2.common.access.AccessSource;
import eu.nordtal.s2.common.access.PlaytimeWording;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.payment.PaymentRequest;
import eu.nordtal.s2.common.payment.PaymentRequestStatus;
import eu.nordtal.s2.common.payment.PaymentRequests;
import eu.nordtal.s2.discordbot.AdminLog;
import eu.nordtal.s2.discordbot.access.SeasonStart;
import eu.nordtal.s2.discordbot.access.discord.AccessRoles;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** {@link AccessChanges} against this bot: the row, the Discord role, the direct message and the audit entry. */
public final class BotAccessEffects implements AccessChanges {

    private final AccessDirectory access;
    private final AccessRoles roles;
    private final PaymentRequests requests;
    private final AdminLog admin;
    private final SeasonStart seasonStart;
    private final Messages messages;
    private final Messages shared;
    private final org.slf4j.Logger log;

    /**
     * Creates the effects over both views of the same bundle files.
     *
     * @param messages this bot's layered bundle
     * @param shared {@code :commands}' bundle as the command inbox renders it, reloaded together with {@code messages}
     */
    public BotAccessEffects(
            final AccessDirectory access,
            final AccessRoles roles,
            final PaymentRequests requests,
            final AdminLog admin,
            final SeasonStart seasonStart,
            final Messages messages,
            final Messages shared,
            final org.slf4j.Logger log) {
        this.access = access;
        this.roles = roles;
        this.requests = requests;
        this.admin = admin;
        this.seasonStart = seasonStart;
        this.messages = messages;
        this.shared = shared;
        this.log = log;
    }

    /** Grants access: the row, the role, the direct message and the admin channel line. */
    @Override
    public Instant grant(final String discordId, final int days, final Actor by) {
        final AccessGrant granted = access.grantAccess(discordId, days, AccessSource.ADMIN, null);
        seasonStart.warnIfUnanchored(discordId, granted);
        roles.applyAccessRole(discordId, true);
        roles.dm(
                discordId,
                messages.format(
                        roles.localeOf(discordId),
                        MESSAGES.dm()
                                .grantedSection()
                                .admin(String.valueOf(days), AccessRoles.timestamp(granted.validUntil()))));

        admin.record("GRANT_ACCESS", by.filed(), discordId, by.minecraftUuid(), days + " days");
        admin.note(by.mention() + " granted <@" + discordId + "> " + days + " days of access, until "
                + AccessRoles.timestamp(granted.validUntil()) + ".");
        return granted.validUntil();
    }

    /** Returns how many grants were revoked, zero included. */
    @Override
    public int revoke(final String discordId, final Actor by) {
        final int revoked = access.revokeAccess(discordId);
        roles.applyAccessRole(discordId, false);
        if (revoked > 0) {
            roles.dm(
                    discordId,
                    messages.format(roles.localeOf(discordId), MESSAGES.dm().revoked()));
        }

        admin.record("REVOKE_ACCESS", by.filed(), discordId, by.minecraftUuid(), revoked + " grant(s)");
        admin.note(by.mention() + " revoked <@" + discordId + ">'s access (" + revoked + " grant(s)).");
        return revoked;
    }

    /** Returns whether there was a link to break. */
    @Override
    public boolean unlink(final String discordId, final Actor by) {
        // Read before the unlink: afterwards only the audit entry keeps the UUID.
        final Optional<UUID> linked = access.linkedMinecraftAccount(discordId);
        if (!access.unlink(discordId)) {
            return false;
        }
        admin.record("UNLINK", by.filed(), discordId, linked.orElse(null), "by an admin, not self-service");
        admin.note(by.mention() + " unlinked <@" + discordId + ">'s Minecraft account `"
                + linked.map(UUID::toString).orElse("?") + "`.");
        return true;
    }

    /** Books a payment by hand, for whoever asked. */
    @Override
    public Settled settle(final String reference, final Actor by) {
        final Optional<PaymentRequest> request = requests.byReference(reference);
        if (request.isEmpty()) {
            return new Settled(Settlement.UNKNOWN, null, 0, null);
        }
        final PaymentRequest found = request.get();
        if (found.status() != PaymentRequestStatus.OPEN) {
            return new Settled(
                    Settlement.NOT_OPEN, null, found.days(), found.status().name());
        }

        requests.settleManually(found.id());
        final AccessGrant granted =
                access.grantAccess(found.discordId(), found.days(), AccessSource.PURCHASE, found.id());
        seasonStart.warnIfUnanchored(found.discordId(), granted);
        roles.applyAccessRole(found.discordId(), true);
        if (found.donationCents() > 0) {
            access.setDonor(found.discordId(), true);
            roles.grantDonorRole(found.discordId());
        }
        roles.dm(
                found.discordId(),
                messages.format(
                        roles.localeOf(found.discordId()),
                        MESSAGES.dm().granted(AccessRoles.timestamp(granted.validUntil()))));

        admin.record(
                "SETTLE",
                by.filed(),
                found.discordId(),
                by.minecraftUuid(),
                "manual, reference=" + reference + " days=" + found.days());
        admin.note(by.mention() + " settled `" + reference + "` by hand: " + found.days() + " days for <@"
                + found.discordId() + ">.");
        return new Settled(
                Settlement.BOOKED,
                granted.validUntil(),
                found.days(),
                found.status().name());
    }

    /**
     * Writes somebody's total play time, without a direct message.
     *
     * @param seconds the new total, which is what the column holds
     */
    @Override
    public void setPlaytime(final String discordId, final long seconds, final Actor by) {
        access.setPlaytimeSeconds(discordId, seconds);
        // Days, hours and minutes, the unit Steward uses.
        admin.record("SET_PLAYTIME", by.filed(), discordId, by.minecraftUuid(), PlaytimeWording.of(seconds));
        admin.note(by.mention() + " set <@" + discordId + ">'s play time to " + PlaytimeWording.of(seconds) + ".");
    }

    @Override
    public boolean reloadMessages() {
        try {
            messages.reload();
            shared.reload();
            return true;
        } catch (final RuntimeException failure) {
            log.error("the messages could not be reloaded, the running ones are unchanged", failure);
            return false;
        }
    }

    @Override
    public List<String> unknownOverrideKeys() {
        return List.copyOf(messages.unknownOverrideKeys());
    }
}
