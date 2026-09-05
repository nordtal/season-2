package eu.nordtal.s2.discordbot.discord;

import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.commands.access.AccessEffects;
import eu.nordtal.s2.common.access.AccessDirectory;
import eu.nordtal.s2.common.access.AccessGrant;
import eu.nordtal.s2.common.access.AccessSource;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.discordbot.access.SeasonStart;
import eu.nordtal.s2.discordbot.access.discord.AccessRoles;
import eu.nordtal.s2.discordbot.access.bunq.Money;
import eu.nordtal.s2.discordbot.access.payment.PaymentRequest;
import eu.nordtal.s2.discordbot.access.payment.PaymentRequestStatus;
import eu.nordtal.s2.discordbot.access.payment.PaymentRequests;

import net.dv8tion.jda.api.JDA;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;

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
public final class BotAccessEffects implements AccessEffects {

    private final Executor executor;
    private final JDA jda;
    private final AccessDirectory access;
    private final AccessRoles roles;
    private final PaymentRequests requests;
    private final AdminLog admin;
    private final SeasonStart seasonStart;
    private final Messages messages;
    private final org.slf4j.Logger log;

    public BotAccessEffects(final Executor executor, final JDA jda, final AccessDirectory access,
                            final AccessRoles roles, final PaymentRequests requests,
                            final AdminLog admin, final SeasonStart seasonStart,
                            final Messages messages, final org.slf4j.Logger log) {
        this.executor = executor;
        this.jda = jda;
        this.access = access;
        this.roles = roles;
        this.requests = requests;
        this.admin = admin;
        this.seasonStart = seasonStart;
        this.messages = messages;
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
    public Optional<String> discordIdOf(final UUID minecraftAccount) {
        return access.linkedDiscordAccount(minecraftAccount);
    }

    @Override
    public Optional<Status> status(final String discordId) {
        // The name comes from Discord and everything else from the database. A member who has left
        // the guild is why this can be empty: the link is still a row and the person is gone, which
        // is a different answer from "not linked" and gets a different sentence.
        final var member = jda.retrieveUserById(discordId).complete();
        if (member == null) {
            return Optional.empty();
        }

        final List<Grant> grants = access.grantsOf(discordId).stream()
                .map(grant -> new Grant(grant.validFrom(), grant.validUntil(),
                        grant.source().name(), grant.revoked() != null))
                .toList();
        final List<Purchase> purchases = requests.recentOf(discordId, 5).stream()
                .map(request -> new Purchase(request.reference(), request.days(),
                        Money.format(request.amountCents()), request.status().name()))
                .toList();

        return Optional.of(new Status(member.getName(),
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
        final AccessGrant granted =
                access.grantAccess(discordId, days, AccessSource.ADMIN, null);
        seasonStart.warnIfUnanchored(discordId, granted);
        roles.applyAccessRole(discordId, true);
        roles.dm(discordId, messages.format(roles.localeOf(discordId), "dm.granted.admin",
                "days", String.valueOf(days),
                "until", AccessRoles.timestamp(granted.validUntil())));

        admin.record("GRANT_ACCESS", actor(by), discordId, by.minecraftUuid().orElse(null),
                days + " days");
        admin.note(mention(by) + " granted <@" + discordId + "> " + days + " days of access, until "
                + AccessRoles.timestamp(granted.validUntil()) + ".");
        return granted.validUntil();
    }

    @Override
    public int revoke(final String discordId, final NordtalUser by) {
        final int revoked = access.revokeAccess(discordId);
        roles.applyAccessRole(discordId, false);
        if (revoked > 0) {
            roles.dm(discordId, messages.get(roles.localeOf(discordId), "dm.revoked"));
        }

        admin.record("REVOKE_ACCESS", actor(by), discordId, by.minecraftUuid().orElse(null),
                revoked + " grant(s)");
        admin.note(mention(by) + " revoked <@" + discordId + ">'s access (" + revoked
                + " grant(s)).");
        return revoked;
    }

    @Override
    public boolean unlink(final String discordId, final NordtalUser by) {
        // Read before the unlink: afterwards there is no row to read it from, and the audit entry is
        // the only place the UUID survives.
        final Optional<UUID> linked = access.linkedMinecraftAccount(discordId);
        if (!access.unlink(discordId)) {
            return false;
        }
        admin.record("UNLINK", actor(by), discordId, linked.orElse(null),
                "by an admin, not self-service");
        admin.note(mention(by) + " unlinked <@" + discordId + ">'s Minecraft account `"
                + linked.map(UUID::toString).orElse("?") + "`.");
        return true;
    }

    @Override
    public List<String> openReferences() {
        return requests.allOpen().stream().map(PaymentRequest::reference).toList();
    }

    @Override
    public Settled settle(final String reference, final NordtalUser by) {
        final Optional<PaymentRequest> request = requests.byReference(reference);
        if (request.isEmpty()) {
            return new Settled(Settlement.UNKNOWN, null, 0, null);
        }
        final PaymentRequest found = request.get();
        if (found.status() != PaymentRequestStatus.OPEN) {
            return new Settled(Settlement.NOT_OPEN, null, found.days(), found.status().name());
        }

        requests.settleManually(found.id());
        final AccessGrant granted = access.grantAccess(found.discordId(), found.days(),
                AccessSource.PURCHASE, found.id());
        seasonStart.warnIfUnanchored(found.discordId(), granted);
        roles.applyAccessRole(found.discordId(), true);
        if (found.donationCents() > 0) {
            access.setDonor(found.discordId(), true);
            roles.grantDonorRole(found.discordId());
        }
        roles.dm(found.discordId(), messages.format(roles.localeOf(found.discordId()), "dm.granted",
                "until", AccessRoles.timestamp(granted.validUntil())));

        admin.record("SETTLE", actor(by), found.discordId(), by.minecraftUuid().orElse(null),
                "manual, reference=" + reference + " days=" + found.days());
        admin.note(mention(by) + " settled `" + reference + "` by hand: " + found.days()
                + " days for <@" + found.discordId() + ">.");
        return new Settled(Settlement.BOOKED, granted.validUntil(), found.days(),
                found.status().name());
    }

    @Override
    public boolean reloadMessages() {
        try {
            messages.reload();
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

    /**
     * Who to file this against.
     *
     * <p>A Discord id when there is one - which is every admin, since the login gate refuses an
     * unlinked player - and the readable name otherwise, which is the console. The column is free
     * text for exactly this reason: a foreign key would mean an action taken from a game surface by
     * an admin who has not linked could not be recorded at all.</p>
     */
    private static String actor(final NordtalUser by) {
        return by.discordId().orElseGet(by::name);
    }

    /** The same, as something that renders in the admin channel. */
    private static String mention(final NordtalUser by) {
        return by.discordId().map(id -> "<@" + id + ">").orElseGet(() -> "`" + by.name() + "`");
    }
}
