package eu.nordtal.season.proxy.gate;

import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import eu.nordtal.season.common.time.Waiting;
import eu.nordtal.season.proxy.NetworkServers;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import net.kyori.adventure.text.Component;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Handles a player whom a backend kicked: a reason disconnects, no reason moves them to a waiting room.
 * A waiting room that refuses an arriving player is waited for; one that drops a player hands them to the other.
 * No redirect leads to a server that an update run stops while the other waiting room runs.
 */
public final class BackendKick {

    enum Decision {
        LEAVE,
        /** Disconnect the player with the reason of the backend. */
        SHOW_REASON,
        /** Move the player to the waiting room. */
        TO_LIMBO
    }

    private final ProxyServer proxy;
    private final NetworkServers servers;
    private final BackendHealth health;
    private final GateMessages messages;
    private final LoginRoster roster;
    private final Logger logger;
    private final Waiting clock;

    /** Whether an update run stops the server. */
    private final Predicate<String> stopping;

    /** @param stopping {@code Evacuation#isMoving} */
    public BackendKick(
            final ProxyServer proxy,
            final NetworkServers servers,
            final BackendHealth health,
            final GateMessages messages,
            final LoginRoster roster,
            final Logger logger,
            final Waiting clock,
            final Predicate<String> stopping) {
        this.proxy = Objects.requireNonNull(proxy, "proxy");
        this.servers = Objects.requireNonNull(servers, "servers");
        this.health = Objects.requireNonNull(health, "health");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.roster = Objects.requireNonNull(roster, "roster");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.stopping = Objects.requireNonNull(stopping, "stopping");
    }

    /** Returns the wait for a waiting room that refused an arriving player, otherwise {@code null}. */
    @Subscribe
    public @Nullable EventTask onKickedFromServer(final KickedFromServerEvent event) {
        final @Nullable Component reason = event.getServerKickReason().orElse(null);
        switch (decide(event.getResult(), reason)) {
            case SHOW_REASON ->
                event.setResult(KickedFromServerEvent.DisconnectPlayer.create(
                        Objects.requireNonNull(reason, "SHOW_REASON only happens when a reason was given")));
            case TO_LIMBO -> {
                return toLimbo(event);
            }
            case LEAVE -> avoidStopping(event);
        }
        return null;
    }

    /**
     * Returns the first usable waiting room besides {@code besides}, {@code limbo} first, otherwise {@code null}.
     *
     * @param besides the room the player left, {@code null} for none
     * @param usable whether the proxy can send a player to the server
     */
    static @Nullable String room(
            final @Nullable String besides, final NetworkServers servers, final Predicate<String> usable) {
        for (final String room : List.of(servers.limbo(), servers.limboStandby())) {
            if (!room.equals(besides) && usable.test(room)) {
                return room;
            }
        }
        return null;
    }

    /** Returns whether the server is registered, not suspended after a drop, and no run stops it. */
    private boolean usable(final String server) {
        return !stopping.test(server)
                && !health.isSuspended(server)
                && proxy.getServer(server).isPresent();
    }

    /** Turns Velocity's redirect to a server that a run stops, from its try list, to a waiting room the run spares. */
    private void avoidStopping(final KickedFromServerEvent event) {
        if (!(event.getResult() instanceof final KickedFromServerEvent.RedirectPlayer redirect)) {
            return;
        }
        final String to = redirect.getServer().getServerInfo().getName();
        if (!stopping.test(to)) {
            return;
        }
        final RegisteredServer room = registered(room(to, servers, this::usable));
        if (room == null) {
            return;
        }
        logger.info(
                "redirect away from stopping server: player={} from={} stopping={} to={}",
                event.getPlayer().getUsername(),
                event.getServer().getServerInfo().getName(),
                to,
                room.getServerInfo().getName());
        final @Nullable Component message = redirect.getMessageComponent();
        event.setResult(
                message == null
                        ? KickedFromServerEvent.RedirectPlayer.create(room)
                        : KickedFromServerEvent.RedirectPlayer.create(room, message));
    }

    private @Nullable RegisteredServer registered(final @Nullable String server) {
        return server == null ? null : proxy.getServer(server).orElse(null);
    }

    /**
     * Returns the decision.
     *
     * @param reason the reason of the backend, {@code null} without one
     */
    static Decision decide(final KickedFromServerEvent.ServerKickResult result, final @Nullable Component reason) {
        if (!(result instanceof KickedFromServerEvent.DisconnectPlayer)) {
            return Decision.LEAVE;
        }
        return reason == null ? Decision.TO_LIMBO : Decision.SHOW_REASON;
    }

    /** Suspends the backend and moves the player to the waiting room. */
    private @Nullable EventTask toLimbo(final KickedFromServerEvent event) {
        final String from = event.getServer().getServerInfo().getName();
        if (servers.isWaitingRoom(from) && arriving(event)) {
            return EventTask.async(() -> awaitWaitingRoom(event, from));
        }
        if (servers.isWaitingRoom(from)) {
            // Suspended, so two rooms that both drop the player do not hand them back and forth.
            health.suspend(from);
            final RegisteredServer other = registered(room(from, servers, this::usable));
            if (other == null) {
                logger.warn(
                        "waiting room lost player without reason, no other room, velocity result kept: server={}"
                                + " player={}",
                        from,
                        event.getPlayer().getUsername());
                return null;
            }
            logger.warn(
                    "waiting room lost player without reason, sent to other room: server={} player={} to={}",
                    from,
                    event.getPlayer().getUsername(),
                    other.getServerInfo().getName());
            event.setResult(KickedFromServerEvent.RedirectPlayer.create(other));
            return null;
        }

        final String spared = room(null, servers, this::usable);
        final RegisteredServer target = registered(
                spared != null
                        ? spared
                        : room(null, servers, name -> proxy.getServer(name).isPresent()));
        if (target == null) {
            logger.error(
                    "player lost without reason, no waiting room registered: player={} from={} waiting_room={}"
                            + " standby_room={}",
                    event.getPlayer().getUsername(),
                    from,
                    servers.limbo(),
                    servers.limboStandby());
            return null;
        }

        health.suspend(from);
        logger.warn(
                "player lost without reason, moved to waiting room, server suspended: player={} from={} to={}"
                        + " seconds={}",
                event.getPlayer().getUsername(),
                from,
                target.getServerInfo().getName(),
                BackendHealth.RETRY.toSeconds());
        final Locale locale = roster.localeOf(event.getPlayer().getUniqueId());
        event.setResult(KickedFromServerEvent.RedirectPlayer.create(target, messages.connectionLost(locale)));
        return null;
    }

    /** Sends the arriving player to the first waiting room that answers, or disconnects them with our own text. */
    private void awaitWaitingRoom(final KickedFromServerEvent event, final String from) {
        final Player player = event.getPlayer();
        final Optional<RegisteredServer> room = WaitingRooms.answering(proxy, servers, clock, WaitingRooms.ON_ARRIVAL);
        if (room.isPresent()) {
            logger.info(
                    "arrival refused, player sent to waiting room: from={} player={} to={}",
                    from,
                    player.getUsername(),
                    room.get().getServerInfo().getName());
            event.setResult(KickedFromServerEvent.RedirectPlayer.create(room.get()));
            return;
        }
        logger.warn(
                "arrival refused, no waiting room answered, player disconnected: from={} player={} seconds={}",
                from,
                player.getUsername(),
                WaitingRooms.ON_ARRIVAL.toSeconds());
        event.setResult(KickedFromServerEvent.DisconnectPlayer.create(
                messages.restarting(roster.localeOf(player.getUniqueId()))));
    }

    /** Returns whether the player was refused on the way in, with no server to fall back on. */
    private static boolean arriving(final KickedFromServerEvent event) {
        return event.kickedDuringServerConnect()
                && event.getPlayer().getCurrentServer().isEmpty();
    }
}
