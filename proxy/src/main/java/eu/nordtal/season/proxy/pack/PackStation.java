package eu.nordtal.season.proxy.pack;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.PlayerResourcePackStatusEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import eu.nordtal.season.common.SeasonPhase;
import eu.nordtal.season.limboprotocol.LimboProtocol;
import eu.nordtal.season.proxy.config.PackSpec;
import eu.nordtal.season.proxy.gate.BackendHealth;
import eu.nordtal.season.proxy.gate.LoginRoster;
import eu.nordtal.season.proxy.phase.PhaseWatch;
import eu.nordtal.season.proxy.routing.PhaseRouting;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Keeps each player in {@code limbo} until the pack is applied and the backend of the phase accepts the player. Each
 * handler records a fact and asks {@link WaitingBook}.
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

    /** The offer, {@code null} when {@code pack#enabled} is off. */
    private final @Nullable PackOffer offer;

    private final MinecraftChannelIdentifier channel = MinecraftChannelIdentifier.from(LimboProtocol.CHANNEL);

    /** The players whose forged message was logged. */
    private final Set<UUID> reportedForgery = ConcurrentHashMap.newKeySet();

    private volatile Consumer<Player> release = player -> {};

    /** Whether an update run has stopped the backend. */
    private volatile java.util.function.Predicate<String> updating = server -> false;

    /** Whether an admin keeps the backend stopped. */
    private volatile java.util.function.Predicate<String> held = server -> false;

    public PackStation(
            final ProxyServer proxy,
            final Logger logger,
            final PhaseRouting routing,
            final PhaseWatch phases,
            final LoginRoster roster,
            final PackMessages messages,
            final PackSpec config,
            final @Nullable PackOffer offer,
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

    /** Registers {@code nordtal:limbo}. */
    public void registerChannel() {
        proxy.getChannelRegistrar().register(channel);
    }

    /** Sets the action for a player who finished waiting. */
    public void onRelease(final Consumer<Player> release) {
        this.release = Objects.requireNonNull(release, "release");
    }

    /** Sets the check whether an update run has stopped a backend. */
    public void whenUpdating(final java.util.function.Predicate<String> updating) {
        this.updating = Objects.requireNonNull(updating, "updating");
    }

    /** Sets the check whether an admin keeps a backend stopped. */
    public void whenHeld(final java.util.function.Predicate<String> held) {
        this.held = Objects.requireNonNull(held, "held");
    }

    /** Returns whether the player waits in the waiting room. */
    public boolean isHeld(final UUID uuid) {
        return book.isWaiting(uuid);
    }

    // arriving in the waiting room

    @Subscribe
    public void onServerPostConnect(final ServerPostConnectEvent event) {
        final Player player = event.getPlayer();
        final UUID uuid = player.getUniqueId();

        if (!onLimbo(player)) {
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
            case ACCEPTED, DOWNLOADED -> {}
            case DECLINED -> {
                logger.info("{} declined the resource pack", player.getUsername());
                disconnect(player, messages.declined(locale));
            }
            case FAILED_DOWNLOAD, FAILED_RELOAD -> {
                logger.warn("{} could not apply the resource pack: {}", player.getUsername(), event.getStatus());
                disconnect(player, messages.failedDownload(locale));
            }
            case INVALID_URL -> {
                logger.error(
                        "The client of {} reports pack#url as unloadable. EVERY player will "
                                + "fail this way until it is fixed.",
                        player.getUsername());
                disconnect(player, messages.invalidUrl(locale));
            }
            case DISCARDED -> {
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
            logger.warn(
                    "'{}' sent a {} on {}, which only the proxy sends",
                    connection.getServerInfo().getName(),
                    message.get().type(),
                    LimboProtocol.CHANNEL);
            return;
        }

        final Player player = connection.getPlayer();
        if (book.ready(player.getUniqueId())) {
            logger.info(
                    "'{}' reported {} ready before the proxy had finished putting them in the "
                            + "waiting room; remembered rather than dropped",
                    connection.getServerInfo().getName(),
                    player.getUsername());
        }
        evaluate(player);
    }

    private void reportForgery(final PluginMessageEvent event) {
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
     * Evaluates every player in the waiting room. This enforces the pack timeout.
     *
     * @return the number of evaluated players
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

    /** Executes the decision of {@link WaitingBook} for the player. */
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
        final boolean admin = roster.isAdmin(uuid);
        final String destination = routing.servers().forAdmitted(phase, admin);
        final boolean available = proxy.getServer(destination).isPresent() && !health.isSuspended(destination);
        final WaitingDecision decision = book.decide(
                uuid, phase, admin, available, destination, updating.test(destination), held.test(destination));

        switch (decision.action()) {
            case IDLE -> {}
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
                logger.warn(
                        "'{}' did not accept a {} message; is the limbo plugin running there?",
                        connection.getServerInfo().getName(),
                        LimboProtocol.CHANNEL);
            }
        });
    }

    /** Returns the player to the waiting list after a failed release connection and suspends the backend. */
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

    /** Returns whether the player is on {@code limbo} or {@code limbo-standby}. */
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
        book.left(player.getUniqueId());
        player.disconnect(reason);
    }
}
