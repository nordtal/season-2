package eu.nordtal.s2.discordbot.discord;

import static eu.nordtal.s2.discordbot.AccessMessages.MESSAGES;

import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.commands.access.AccessEffects;
import eu.nordtal.s2.common.access.AccessDirectory;
import eu.nordtal.s2.common.access.AccessGrant;
import eu.nordtal.s2.common.access.AccessSource;
import eu.nordtal.s2.common.access.PlaytimeWording;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.payment.Money;
import eu.nordtal.s2.common.payment.PaymentRequest;
import eu.nordtal.s2.common.payment.PaymentRequestStatus;
import eu.nordtal.s2.common.payment.PaymentRequests;
import eu.nordtal.s2.discordbot.access.SeasonStart;
import eu.nordtal.s2.discordbot.access.discord.AccessRoles;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import net.dv8tion.jda.api.JDA;

/**
 * {@link AccessEffects} against this bot.
 *
 * <h2>Everything here is three things at once</h2>
 * A grant is a row, a Discord role and a direct message in the recipient's own language; a
 * revocation is the same three in reverse. Only this process holds a JDA session, which is why these
 * are the bot's effects and not {@code :common}'s - and why a Paper server asking for one writes a
 * {@code command_request} row rather than doing it itself.
 *
 * <h2>The audit row is written here and not by the command</h2>
 * {@code audit_log} is this bot's, and its shape - action, actor, subject, detail - is a Discord
 * shape. A command that built one would be a command that knows what a Discord id is for; the
 * command hands over a {@link NordtalUser} and this decides what to file.
 *
 * <h2>Two instances, as everywhere</h2>
 * The one behind the slash commands runs its work on the bot's worker pool, because a JDA gateway
 * thread has three seconds. The one behind the command inbox runs it inline, because the inbox
 * settles a request row when the command returns.
 */
public final class BotAccessEffects implements AccessEffects, AccessChanges {

    private final Executor executor;
    private final JDA jda;
    private final AccessDirectory access;
    private final AccessRoles roles;
    private final PaymentRequests requests;
    private final AdminLog admin;
    private final SeasonStart seasonStart;
    private final Messages messages;
    private final Messages shared;
    private final org.slf4j.Logger log;

    /**
     * @param messages this bot's layered bundle - what it says on its own surface
     * @param shared   {@code :commands}' bundle as the command inbox renders it. Two views of the
     *                 same files, so a reload that moved only one of them would leave a command
     *                 answering differently in Discord and in game
     */
    public BotAccessEffects(
            final Executor executor,
            final JDA jda,
            final AccessDirectory access,
            final AccessRoles roles,
            final PaymentRequests requests,
            final AdminLog admin,
            final SeasonStart seasonStart,
            final Messages messages,
            final Messages shared,
            final org.slf4j.Logger log) {
        this.executor = executor;
        this.jda = jda;
        this.access = access;
        this.roles = roles;
        this.requests = requests;
        this.admin = admin;
        this.seasonStart = seasonStart;
        this.messages = messages;
        this.shared = shared;
        this.log = log;
    }

    @Override
    public void async(final Runnable work) {
        executor.execute(work);
    }

    @Override
    public void warn(final String what, final Throwable failure) {
        log.warn(what, failure);
    }

    @Override
    public Optional<Status> status(final String discordId) {
        // The name comes from Discord and everything else from the database. A member who has left
        // the guild is why this can be empty: the link is still a row and the person is gone, which
        // is a different answer from "not linked" and gets a different sentence.
        //
        // Through the guild, not through JDA's global user lookup - see AccessRoles#member. The
        // global one answers for anybody with a Discord account, so it could not tell a departed
        // member apart from a present one, and it throws rather than returning null for an id
        // nobody has.
        final Optional<net.dv8tion.jda.api.entities.Member> member = roles.member(discordId);
        if (member.isEmpty()) {
            return Optional.empty();
        }

        final List<Grant> grants = access.grantsOf(discordId).stream()
                .map(grant -> new Grant(
                        grant.validFrom(), grant.validUntil(), grant.source().name(), grant.revoked() != null))
                .toList();
        final List<Purchase> purchases = requests.recentOf(discordId, 5).stream()
                .map(request -> new Purchase(
                        request.reference(),
                        request.days(),
                        Money.format(request.amountCents()),
                        request.status().name()))
                .toList();

        return Optional.of(new Status(
                member.get().getUser().getName(),
                grants.stream()
                        .filter(grant -> !grant.revoked())
                        .map(Grant::validUntil)
                        .filter(until -> until.isAfter(Instant.now()))
                        .max(Instant::compareTo),
                access.isDonor(discordId),
                roles.localeOf(discordId),
                access.linkedMinecraftAccount(discordId),
                grants,
                purchases));
    }

    @Override
    public Instant grant(final String discordId, final int days, final NordtalUser by) {
        return grant(discordId, days, Actor.of(by));
    }

    /**
     * The grant itself, for whoever asked (season-2-community/08).
     *
     * <p>Four things, and only this process can do three of them: the row, the role, the direct
     * message in the recipient's own language, and the line in the admin channel. A surface that
     * wrote only the first was the defect this seam closes.</p>
     */
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

    @Override
    public int revoke(final String discordId, final NordtalUser by) {
        return revoke(discordId, Actor.of(by));
    }

    /** @return how many grants were revoked - zero is a legitimate answer and worth saying. */
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

    @Override
    public boolean unlink(final String discordId, final NordtalUser by) {
        return unlink(discordId, Actor.of(by));
    }

    /** @return whether there was a link to break. */
    @Override
    public boolean unlink(final String discordId, final Actor by) {
        // Read before the unlink: afterwards there is no row to read it from, and the audit entry is
        // the only place the UUID survives.
        final Optional<UUID> linked = access.linkedMinecraftAccount(discordId);
        if (!access.unlink(discordId)) {
            return false;
        }
        admin.record("UNLINK", by.filed(), discordId, linked.orElse(null), "by an admin, not self-service");
        admin.note(by.mention() + " unlinked <@" + discordId + ">'s Minecraft account `"
                + linked.map(UUID::toString).orElse("?") + "`.");
        return true;
    }

    @Override
    public List<String> openReferences() {
        return requests.allOpen().stream().map(PaymentRequest::reference).toList();
    }

    @Override
    public Settled settle(final String reference, final NordtalUser by) {
        return settle(reference, Actor.of(by));
    }

    /** Book a payment by hand, for whoever asked. */
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
     * Write somebody's total play time (season-2-community/08).
     *
     * <p>New here, and it was nowhere before: steward wrote the column and a journal line, and the
     * admin channel never heard about it. It joins the other four so that "an access change" means
     * the same set of consequences whoever asked for it.</p>
     *
     * <p><b>No direct message.</b> The other four change what somebody may do and they are told;
     * this corrects a number that is only ever read by admins, and a DM saying "your play time is
     * now 42 hours" is an interruption about nothing. The tier it derives into is visible in game
     * the moment it changes, which is the only part a player would notice.</p>
     *
     * @param seconds the new total, which is what the column holds
     */
    @Override
    public void setPlaytime(final String discordId, final long seconds, final Actor by) {
        access.setPlaytimeSeconds(discordId, seconds);
        // Days, hours and minutes and not a number of seconds: Steward's dialog asks in those
        // units and its list answers in them, so the journal and the admin channel do too.
        admin.record("SET_PLAYTIME", by.filed(), discordId, by.minecraftUuid(), PlaytimeWording.of(seconds));
        admin.note(by.mention() + " set <@" + discordId + ">'s play time to " + PlaytimeWording.of(seconds) + ".");
    }

    @Override
    public boolean reloadMessages() {
        try {
            messages.reload();
            // The command inbox's own view of the shared bundle, in the same breath. Its unknown
            // keys are deliberately not reported: it holds one root, so a key this module declares
            // would be named as unknown by it and is not.
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
