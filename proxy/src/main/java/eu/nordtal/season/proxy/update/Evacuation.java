package eu.nordtal.season.proxy.update;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import eu.nordtal.season.database.update.UpdateDirectory;
import eu.nordtal.season.database.update.UpdateRequest;
import eu.nordtal.season.proxy.PhaseServers;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Moves the players of a server that an update run stops to the waiting room when the countdown reaches zero.
 * The sweep of the waiting room sends them back.
 */
public final class Evacuation {

    private final ProxyServer proxy;
    private final Logger logger;
    private final UpdateDirectory updates;
    private final PhaseServers servers;

    /** Informs the players when they return. */
    private final Homecoming homecoming;
    /** The servers that an update run stops or is about to stop. */
    private volatile Set<String> moving = Set.of();

    /** The servers that an admin keeps stopped. */
    private volatile Set<String> held = Set.of();

    /** Whether the warning about the missing waiting room was logged. */
    private volatile boolean warnedAboutWaitingRoom;

    public Evacuation(
            final ProxyServer proxy,
            final Logger logger,
            final UpdateDirectory updates,
            final PhaseServers servers,
            final Homecoming homecoming) {
        this.proxy = Objects.requireNonNull(proxy, "proxy");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.updates = Objects.requireNonNull(updates, "updates");
        this.servers = Objects.requireNonNull(servers, "servers");
        this.homecoming = Objects.requireNonNull(homecoming, "homecoming");
    }

    /** Returns whether an update run stops or has stopped the server. */
    public boolean isMoving(final String server) {
        return server != null && moving.contains(server);
    }

    /** Returns whether an update run stops or has stopped any server. */
    public boolean isAnyMoving() {
        return !moving.isEmpty();
    }

    /** Returns whether an admin keeps the server stopped. */
    public boolean isHeld(final String server) {
        return server != null && held.contains(server);
    }

    /** Reads the update row and moves the players when the countdown has ended. It never throws. */
    public synchronized void check() {
        final Optional<UpdateRequest> running;
        try {
            held = heldServices(updates.holds());
            running = updates.running();
        } catch (final RuntimeException failure) {
            logger.warn("Could not read the update row; nobody was moved this pass", failure);
            return;
        }

        final Set<String> next = imminent(running);
        if (next.isEmpty()) {
            moving = Set.of();
            warnedAboutWaitingRoom = false;
            return;
        }

        final String room = roomFor(
                next,
                servers.limbo(),
                servers.limboStandby(),
                name -> proxy.getServer(name).isPresent());
        if (room == null) {
            if (!warnedAboutWaitingRoom) {
                warnedAboutWaitingRoom = true;
                logger.warn(
                        "The update moves '{}' itself and no '{}' is registered on this proxy,"
                                + " so there is nowhere to put anybody: nobody is being evacuated"
                                + " and every connected player will be disconnected when the"
                                + " servers stop. The countdown is all the warning they get.",
                        servers.limbo(),
                        servers.limboStandby());
            }
            moving = Set.of();
            return;
        }

        moving = next;
        evacuate(next, room, running.orElseThrow().id());
    }

    /**
     * Returns {@code limbo} unless the run stops it, otherwise the standby if registered, otherwise {@code null}.
     *
     * @param next the servers that the run stops
     * @param registered whether the proxy has a server of this name
     */
    static @Nullable String roomFor(
            final Set<String> next, final String limbo, final String standby, final Predicate<String> registered) {
        if (!next.contains(limbo) && registered.test(limbo)) {
            return limbo;
        }
        if (!next.contains(standby) && registered.test(standby)) {
            return standby;
        }
        return null;
    }

    /** Returns the services of the holds. */
    static Set<String> heldServices(final List<eu.nordtal.season.database.update.ServiceHold> holds) {
        if (holds.isEmpty()) {
            return Set.of();
        }
        final Set<String> services = new HashSet<>();
        for (final eu.nordtal.season.database.update.ServiceHold hold : holds) {
            services.add(hold.service());
        }
        return Set.copyOf(services);
    }

    /**
     * Returns the servers to clear, or an empty set without a running request.
     *
     * @param running the request whose countdown has ended
     */
    static Set<String> imminent(final Optional<UpdateRequest> running) {
        return running.map(Evacuation::backends).orElseGet(Set::of);
    }

    /** Returns the services that the request stops. */
    static Set<String> backends(final UpdateRequest request) {
        return Set.copyOf(request.moving());
    }

    /** Stores the number of moved players. A failed write is logged. */
    private void moved(final long run, final String from, final String room, final int players) {
        try {
            updates.recorder().moved(run, from, room, players);
        } catch (final RuntimeException failure) {
            logger.warn("Could not count the {} player(s) moved off '{}'", players, from, failure);
        }
    }

    /** Moves every player on one of the servers to the waiting room. */
    private void evacuate(final Set<String> backends, final String waitingRoom, final long run) {
        final RegisteredServer limbo = proxy.getServer(waitingRoom).orElse(null);
        if (limbo == null) {
            logger.error(
                    "The update moves {} and there is no '{}' registered on this proxy, so"
                            + " nobody can be moved out of the way",
                    backends,
                    waitingRoom);
            return;
        }

        final List<Player> leaving = new java.util.ArrayList<>();
        final Map<String, Integer> off = new HashMap<>();
        for (final Player player : proxy.getAllPlayers()) {
            final String on = player.getCurrentServer()
                    .map(connection -> connection.getServerInfo().getName())
                    .orElse(null);
            if (on != null && backends.contains(on)) {
                leaving.add(player);
                off.merge(on, 1, Integer::sum);
            }
        }
        if (leaving.isEmpty()) {
            return;
        }

        logger.info(
                "Moving {} player(s) off {} into '{}' before the update stops them",
                leaving.size(),
                new HashSet<>(backends),
                waitingRoom);
        homecoming.movedOut(leaving);
        off.forEach((from, players) -> moved(run, from, waitingRoom, players));
        for (final Player player : leaving) {
            try {
                player.createConnectionRequest(limbo).fireAndForget();
            } catch (final RuntimeException failure) {
                logger.warn(
                        "Could not move {} into '{}' before the update", player.getUsername(), waitingRoom, failure);
            }
        }
    }
}
