package eu.nordtal.season.proxy.update;

import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import eu.nordtal.season.database.update.RunProgress;
import eu.nordtal.season.database.update.RunStep;
import eu.nordtal.season.database.update.UpdateDirectory;
import eu.nordtal.season.database.update.UpdateRequest;
import eu.nordtal.season.proxy.PhaseServers;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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

    /** When this proxy started. */
    private final Instant started;

    /** Informs the players when they return. */
    private final Homecoming homecoming;
    /** The servers that an update run stops or is about to stop. */
    private volatile Set<String> moving = Set.of();

    /** The servers that an admin keeps stopped. */
    private volatile Set<String> held = Set.of();

    /** The players whose move to the waiting room Velocity has not answered yet. */
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

    /** How far the run that moves players is, held at its highest. */
    private volatile OptionalInt progress = OptionalInt.empty();

    /** The run {@link #progress} belongs to. */
    private long progressRun;

    /** Whether the warning about the missing waiting room was logged. */
    private volatile boolean warnedAboutWaitingRoom;

    public Evacuation(
            final ProxyServer proxy,
            final Logger logger,
            final UpdateDirectory updates,
            final PhaseServers servers,
            final Homecoming homecoming,
            final Clock clock) {
        this.proxy = Objects.requireNonNull(proxy, "proxy");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.updates = Objects.requireNonNull(updates, "updates");
        this.servers = Objects.requireNonNull(servers, "servers");
        this.homecoming = Objects.requireNonNull(homecoming, "homecoming");
        this.started = clock.instant();
    }

    /** Returns whether an update run stops or has stopped the server. */
    public boolean isMoving(final String server) {
        return server != null && moving.contains(server);
    }

    /** Returns whether an update run stops or has stopped any server. */
    public boolean isAnyMoving() {
        return !moving.isEmpty();
    }

    /** Returns how far the run that has moved players is, or empty when nothing is known. */
    public OptionalInt progress() {
        return progress;
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
        final List<RunProgress.Step> steps = steps(running);

        final Set<String> next = notYetReplaced(imminent(running), steps);
        if (next.isEmpty()) {
            moving = Set.of();
            progress = OptionalInt.empty();
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
            progress = OptionalInt.empty();
            return;
        }

        moving = next;
        final UpdateRequest run = running.orElseThrow();
        track(run, steps);
        if (startedByTheRun(started, run)) {
            // The proxy this one replaced moved the players already; the rooms may be gone by now.
            return;
        }
        evacuate(next, room, run.id());
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

    /** Returns the higher of the two, so a step row that appears late never lowers the shown percentage. */
    static OptionalInt higher(final OptionalInt held, final OptionalInt now) {
        if (held.isEmpty()) {
            return now;
        }
        if (now.isEmpty()) {
            return held;
        }
        return OptionalInt.of(Math.max(held.getAsInt(), now.getAsInt()));
    }

    /** Returns the moving services whose health check after the replacement has not passed yet. */
    static Set<String> notYetReplaced(final Set<String> moving, final List<RunProgress.Step> steps) {
        final Set<String> left = new HashSet<>(moving);
        for (final RunProgress.Step step : steps) {
            if (step.kind() == RunStep.Kind.VERIFY
                    && (step.outcome() == RunStep.Outcome.DONE || step.outcome() == RunStep.Outcome.WARNING)) {
                left.remove(step.service());
            }
        }
        return Set.copyOf(left);
    }

    /** Returns whether this proxy started after the countdown of the run ended, so the run started it. */
    static boolean startedByTheRun(final Instant started, final UpdateRequest run) {
        return run.countdownEnd() != null && started.isAfter(run.countdownEnd());
    }

    /** Returns the services that the request stops. */
    static Set<String> backends(final UpdateRequest request) {
        return Set.copyOf(request.moving());
    }

    /**
     * Reads the steps of the run, none when the read fails.
     * A newer agent can write a step name this build lacks; every moving service then still counts.
     */
    private List<RunProgress.Step> steps(final Optional<UpdateRequest> running) {
        try {
            return running.map(updates::stepsOf).orElseGet(List::of);
        } catch (final RuntimeException failure) {
            logger.warn("Could not read the steps of the run; every moving service counts", failure);
            return List.of();
        }
    }

    /** Keeps the highest percentage of the run. */
    private void track(final UpdateRequest run, final List<RunProgress.Step> steps) {
        final OptionalInt now = RunProgress.percent(steps, run.moving());
        progress = run.id() == progressRun ? higher(progress, now) : now;
        progressRun = run.id();
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

        final Map<String, Integer> off = new HashMap<>();
        for (final Player player : proxy.getAllPlayers()) {
            final String on = player.getCurrentServer()
                    .map(connection -> connection.getServerInfo().getName())
                    .orElse(null);
            if (on == null || !backends.contains(on)) {
                continue;
            }
            if (homecoming.movedOut(run, player, on)) {
                off.merge(on, 1, Integer::sum);
            }
            move(player, limbo);
        }
        if (!off.isEmpty()) {
            logger.info("Moving players off {} into '{}' before the update stops them: {}", backends, waitingRoom, off);
        }
        off.forEach((from, players) -> moved(run, from, waitingRoom, players));
    }

    /**
     * Asks Velocity to move the player unless a move is already under way.
     * Velocity counts the player on the old server until the move completes, and refuses a second request with its
     * own red text, which {@code connect()} keeps from the player where {@code fireAndForget()} would send it.
     */
    private void move(final Player player, final RegisteredServer room) {
        final UUID uuid = player.getUniqueId();
        if (!inFlight.add(uuid)) {
            return;
        }
        final String name = room.getServerInfo().getName();
        try {
            final var _ = player.createConnectionRequest(room)
                    .connect()
                    .whenComplete((result, failure) -> moveEnded(player, name, result, failure));
        } catch (final RuntimeException failure) {
            moveEnded(player, name, null, failure);
        }
    }

    /** Lets the next pass ask again for a player whose move did not happen. */
    private void moveEnded(
            final Player player,
            final String room,
            final ConnectionRequestBuilder.@Nullable Result result,
            final @Nullable Throwable failure) {
        inFlight.remove(player.getUniqueId());
        if (failure != null) {
            logger.warn("Could not move {} into '{}' before the update", player.getUsername(), room, failure);
        } else if (result != null && !result.isSuccessful()) {
            logger.info(
                    "{} was not moved into '{}' ({}); asking again on the next pass",
                    player.getUsername(),
                    room,
                    result.getStatus());
        }
    }
}
