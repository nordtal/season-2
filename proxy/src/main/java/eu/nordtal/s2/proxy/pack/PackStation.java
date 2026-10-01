package eu.nordtal.s2.proxy.pack;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.PlayerResourcePackStatusEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import eu.nordtal.s2.common.SeasonPhase;
import eu.nordtal.s2.limboprotocol.LimboProtocol;
import eu.nordtal.s2.proxy.config.PackSpec;
import eu.nordtal.s2.proxy.gate.BackendHealth;
import eu.nordtal.s2.proxy.gate.LoginRoster;
import eu.nordtal.s2.proxy.phase.PhaseWatch;
import eu.nordtal.s2.proxy.routing.PhaseRouting;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

/**
 * Holds every player in {@code limbo} until the pack is applied and the phase's backend will have them.
 *
 * Events arrive in any order, so each handler records a fact and re-asks {@link WaitingBook}.
 */
public final class PackStation {

    private final ProxyServer proxy;
    private final Logger logger;
    private final PhaseRouting routing;
    private final PhaseWatch phases;
    private final LoginRoster roster;
    private final PackMessages messages;
    private final PackSpec config;
    private final WaitingBook book;
    private final BackendHealth health;

    /** {@code null} when {@code pack#enabled} is off. */
    private final PackOffer offer;

    private final MinecraftChannelIdentifier channel = MinecraftChannelIdentifier.from(LimboProtocol.CHANNEL);

    /** UUIDs already reported for a forged message, so a spammer logs once. */
    private final Set<UUID> reportedForgery = ConcurrentHashMap.newKeySet();

    private volatile Consumer<Player> release = player -> {};

    /** Whether an update run has that backend stopped; false until {@link #whenUpdating} is called. */
    private volatile java.util.function.Predicate<String> updating = server -> false;

    /** Whether somebody holds that backend down on purpose; false until {@link #whenHeld} is called. */
    private volatile java.util.function.Predicate<String> held = server -> false;

    public PackStation(
            final ProxyServer proxy,
            final Logger logger,
            final PhaseRouting routing,
            final PhaseWatch phases,
            final LoginRoster roster,
            final PackMessages messages,
            final PackSpec config,
            final PackOffer offer,
            final WaitingBook book,
            final BackendHealth health) {
        this.proxy = Objects.requireNonNull(proxy, "proxy");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.routing = Objects.requireNonNull(routing, "routing");
        this.phases = Objects.requireNonNull(phases, "phases");
        this.roster = Objects.requireNonNull(roster, "roster");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.config = Objects.requireNonNull(config, "config");
        this.book = Objects.requireNonNull(book, "book");
        this.health = Objects.requireNonNull(health, "health");
        this.offer = offer;
    }

    /** Registers {@code nordtal:limbo}; otherwise the proxy forwards the channel without seeing it. */
    public void registerChannel() {
        proxy.getChannelRegistrar().register(channel);
    }

    /** Sets what to do with a player who has finished waiting, normally {@code PlayerRouter::releaseFromLimbo}. */
    public void onRelease(final Consumer<Player> release) {
        this.release = Objects.requireNonNull(release, "release");
    }

    /** Sets the per-destination update question, normally {@code Evacuation::isMoving}. */
    public void whenUpdating(final java.util.function.Predicate<String> updating) {
        this.updating = Objects.requireNonNull(updating, "updating");
    }

    /** Sets the per-destination hold question, normally {@code Evacuation::isHeld}. */
    public void whenHeld(final java.util.function.Predicate<String> held) {
        this.held = Objects.requireNonNull(held, "held");
    }

    /** Returns whether this player is held in the waiting room, so a phase change re-examines the hold. */
    public boolean isHeld(final UUID uuid) {
        return book.isWaiting(uuid);
    }

    // arriving in the waiting room

    @Subscribe
    public void onServerPostConnect(final ServerPostConnectEvent event) {
        final Player player = event.getPlayer();
        final UUID uuid = player.getUniqueId();

        if (!onLimbo(player)) {
            // They have left the waiting room, released by us or moved by a phase change.
            book.left(uuid);
            return;
        }

        book.entered(uuid);
        sendOfferIfNeeded(player);
        evaluate(player);
    }

    private void sendOfferIfNeeded(final Player player) {
        if (offer == null) {
            return;
        }
        // A login the fallback cache answered is not in the roster, so it gets the pack.
        if (roster.isPackExempt(player.getUniqueId())) {
            if (book.packExempt(player.getUniqueId())) {
                logger.info("{} is exempt from the resource pack by an admin; no offer sent", player.getUsername());
            }
            return;
        }
        if (!book.claimOffer(player.getUniqueId())) {
            return;
        }
        player.sendResourcePackOffer(offer.forLocale(localeOf(player)));
        logger.debug("Offered the resource pack to {}", player.getUsername());
    }

    // the client's answer

    @Subscribe
    public void onPackStatus(final PlayerResourcePackStatusEvent event) {
        final Player player = event.getPlayer();
        final UUID uuid = player.getUniqueId();
        final Locale locale = localeOf(player);

        switch (event.getStatus()) {
            case SUCCESSFUL -> {
                book.packApplied(uuid);
                evaluate(player);
            }
            case ACCEPTED, DOWNLOADED -> {
                // Intermediate: the player is still working on it.
            }
            case DECLINED -> {
                logger.info("{} declined the resource pack", player.getUsername());
                disconnect(player, messages.declined(locale));
            }
            case FAILED_DOWNLOAD, FAILED_RELOAD -> {
                logger.warn("{} could not apply the resource pack: {}", player.getUsername(), event.getStatus());
                disconnect(player, messages.failedDownload(locale));
            }
            case INVALID_URL -> {
                // Everybody's problem, not this player's: pack#url does not load at all.
                logger.error(
                        "The client of {} reports pack#url as unloadable. EVERY player will "
                                + "fail this way until it is fixed.",
                        player.getUsername());
                disconnect(player, messages.invalidUrl(locale));
            }
            case DISCARDED -> {
                // Only reachable if a backend sends its own pack over ours.
                logger.warn("The resource pack was discarded for {}", player.getUsername());
            }
        }
    }

    // limbo's answer

    @Subscribe
    public void onPluginMessage(final PluginMessageEvent event) {
        if (!channel.equals(event.getIdentifier())) {
            return;
        }

        // Consumed either way: nobody downstream needs this conversation.
        event.setResult(PluginMessageEvent.ForwardResult.handled());

        if (!(event.getSource() instanceof ServerConnection connection)) {
            reportForgery(event);
            return;
        }

        final Optional<LimboProtocol.Message> message = LimboProtocol.decode(event.getData());
        if (message.isEmpty()) {
            logger.warn(
                    "Dropped an unreadable {} message from '{}'",
                    LimboProtocol.CHANNEL,
                    connection.getServerInfo().getName());
            return;
        }
        if (message.get().type() != LimboProtocol.Type.READY) {
            // WAIT runs proxy to limbo only; a backend sending one has a bug.
            logger.warn(
                    "'{}' sent a {} on {}, which only the proxy sends",
                    connection.getServerInfo().getName(),
                    message.get().type(),
                    LimboProtocol.CHANNEL);
            return;
        }

        final Player player = connection.getPlayer();
        if (book.ready(player.getUniqueId())) {
            // The arrival event has not reached us yet; logged as the only evidence this race happens.
            logger.info(
                    "'{}' reported {} ready before the proxy had finished putting them in the "
                            + "waiting room; remembered rather than dropped",
                    connection.getServerInfo().getName(),
                    player.getUsername());
        }
        evaluate(player);
    }

    private void reportForgery(final PluginMessageEvent event) {
        // A client writing on this channel is trying to skip the pack; logged once per player, never acted on.
        final UUID uuid = event.getSource() instanceof Player player ? player.getUniqueId() : null;
        if (uuid == null || reportedForgery.add(uuid)) {
            logger.warn(
                    "Ignored a {} message that did not come from a backend server: {}",
                    LimboProtocol.CHANNEL,
                    event.getSource());
        }
    }

    // the decision

    /**
     * Re-asks the question for every player in the waiting room, which also enforces the pack timeout.
     *
     * @return how many players were looked at
     */
    public int sweep() {
        int seen = 0;
        for (final Player player : proxy.getAllPlayers()) {
            if (book.isWaiting(player.getUniqueId())) {
                evaluate(player);
                seen++;
            }
        }
        return seen;
    }

    /** Carries out whatever {@link WaitingBook} decides for one held player; nothing for anyone else. */
    public void evaluate(final Player player) {
        final UUID uuid = player.getUniqueId();
        if (!book.isWaiting(uuid)) {
            return;
        }
        if (!onLimbo(player)) {
            book.left(uuid);
            return;
        }

        final SeasonPhase phase = phases.lastKnown();
        // The admin flag decides whether maintenance holds them and where a release sends them.
        final boolean admin = roster.isAdmin(uuid);
        final String destination = routing.servers().forAdmitted(phase, admin);
        // Registered is not enough: BackendHealth also holds a backend that just kicked somebody.
        final boolean available = proxy.getServer(destination).isPresent() && !health.isSuspended(destination);
        final WaitingDecision decision = book.decide(
                uuid, phase, admin, available, destination, updating.test(destination), held.test(destination));

        switch (decision.action()) {
            case IDLE -> {
                // Already showing the right title, or waiting out the grace period.
            }
            case SHOW ->
                sendToLimbo(
                        player,
                        LimboProtocol.wait(Objects.requireNonNull(decision.reason(), "SHOW always carries a reason")));
            case TIMED_OUT -> {
                logger.warn(
                        "{} never answered the resource pack offer within {}s",
                        player.getUsername(),
                        config.applyTimeoutSeconds());
                disconnect(player, messages.timedOut(localeOf(player)));
            }
            case RELEASE -> {
                logger.info(
                        "{} has the pack and is leaving the waiting room for '{}'", player.getUsername(), destination);
                release.accept(player);
            }
            case RELEASE_UNCONFIRMED -> {
                // The player goes where they were going; only the channel is wrong.
                logger.warn(
                        "Releasing {} to '{}' without a READY from '{}': everything else has "
                                + "been settled for the grace period. The nordtal:limbo channel is "
                                + "not delivering backend messages to this proxy.",
                        player.getUsername(),
                        destination,
                        routing.servers().limbo());
                release.accept(player);
            }
        }
    }

    private void sendToLimbo(final Player player, final byte[] data) {
        player.getCurrentServer().ifPresent(connection -> {
            if (!connection.sendPluginMessage(channel, data)) {
                // The backend has not registered the channel; release skips WAIT, so nobody is stuck.
                logger.warn(
                        "'{}' did not accept a {} message; is the limbo plugin running there?",
                        connection.getServerInfo().getName(),
                        LimboProtocol.CHANNEL);
            }
        });
    }

    /**
     * Puts a player back on the books after a release's connection failed, and suspends that backend.
     *
     * @param cause why, in one line
     */
    public void releaseFailed(final Player player, final String cause) {
        final String destination =
                routing.servers().forAdmitted(phases.lastKnown(), roster.isAdmin(player.getUniqueId()));
        book.releaseFailed(player.getUniqueId(), destination);
        health.suspend(destination);
        logger.warn(
                "'{}' did not take {} ({}); holding them in the waiting room and trying again in {}s",
                destination,
                player.getUsername(),
                cause,
                WaitingBook.RELEASE_RETRY.toSeconds());
        evaluate(player);
    }

    // housekeeping

    @Subscribe
    public void onDisconnect(final DisconnectEvent event) {
        final UUID uuid = event.getPlayer().getUniqueId();
        book.forget(uuid);
        reportedForgery.remove(uuid);
    }

    /** Whether this player is in either waiting room, {@code limbo} or {@code limbo-standby}. */
    private boolean onLimbo(final Player player) {
        return player.getCurrentServer()
                .map(connection -> connection.getServerInfo().getName())
                .filter(name -> routing.servers().isWaitingRoom(name))
                .isPresent();
    }

    private Locale localeOf(final Player player) {
        return roster.localeOf(player.getUniqueId());
    }

    private void disconnect(final Player player, final Component reason) {
        // Ends the visit before the disconnect, so a concurrent sweep decides nothing else about them.
        book.left(player.getUniqueId());
        player.disconnect(reason);
    }
}
