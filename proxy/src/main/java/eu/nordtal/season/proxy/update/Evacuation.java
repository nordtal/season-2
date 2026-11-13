package eu.nordtal.season.proxy.update;

import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import eu.nordtal.season.common.time.Scheduler;
import eu.nordtal.season.database.inbox.StewardRequest;
import eu.nordtal.season.database.update.RunProgress;
import eu.nordtal.season.database.update.RunStep;
import eu.nordtal.season.database.update.UpdateDirectory;
import eu.nordtal.season.database.update.UpdateKind;
import eu.nordtal.season.database.update.UpdateRequest;
import eu.nordtal.season.database.update.UpdateStatus;
import eu.nordtal.season.proxy.NetworkServers;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Moves the players of a server that an update run stops to the waiting room when the countdown reaches zero.
 * A move counts once it lands; a failed one is tried again after {@link #RETRY}, in the other room if it runs.
 * The sweep of the waiting room sends them back. A mode switch holds every player there until its target is up.
 */
public final class Evacuation {

    /** The wait before a failed move is tried again. */
    static final Duration RETRY = Duration.ofSeconds(2);

    /**
     * The run that moves the players, and where a mode switch takes them.
     *
     * @param switchTo the server a mode switch makes active, {@code null} for another run
     */
    private record Leg(long run, @Nullable String switchTo) {}

    private final ProxyServer proxy;
    private final Scheduler scheduler;
    private final Logger logger;
    private final UpdateDirectory updates;
    private final NetworkServers servers;

    private final Clock clock;

    /** When this proxy started. */
    private final Instant started;

    /** Informs the players when they return. */
    private final Homecoming homecoming;
    /** The servers that an update run stops or is about to stop. */
    private volatile Set<String> moving = Set.of();

    /** The servers that an admin keeps stopped. */
    private volatile Set<String> held = Set.of();

    /** The players whose move to the waiting room Velocity has not answered yet, each with its end. */
    private final Map<UUID, CompletableFuture<Void>> inFlight = new ConcurrentHashMap<>();

    /** The run whose moves are under way, {@code null} while no run moves anyone. */
    private volatile @Nullable Leg leg;

    /** How far the run that moves players is, held at its highest. */
    private volatile OptionalInt progress = OptionalInt.empty();

    /** The run {@link #progress} belongs to. */
    private long progressRun;

    /** Whether a mode switch holds every player in the waiting room. */
    private volatile boolean switching;

    /** Runs when a mode switch stops holding the players. */
    private volatile Runnable whenReleased = () -> {};

    /** The switch run whose target {@link #target} is, {@code -1} for none. */
    private long targetRun = -1;

    /** The server that the switch run {@link #targetRun} makes active. */
    private @Nullable String target;

    /** Whether the warning about the missing waiting room was logged. */
    private volatile boolean warnedAboutWaitingRoom;

    public Evacuation(
            final ProxyServer proxy,
            final Scheduler scheduler,
            final Logger logger,
            final UpdateDirectory updates,
            final NetworkServers servers,
            final Homecoming homecoming,
            final Clock clock) {
        this.proxy = Objects.requireNonNull(proxy, "proxy");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.updates = Objects.requireNonNull(updates, "updates");
        this.servers = Objects.requireNonNull(servers, "servers");
        this.homecoming = Objects.requireNonNull(homecoming, "homecoming");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.started = clock.instant();
    }

    /** Sets the work that runs when a mode switch stops holding the players, such as a sweep. */
    public void whenReleased(final Runnable released) {
        this.whenReleased = Objects.requireNonNull(released, "released");
    }

    /** Returns whether a mode switch holds every player in the waiting room, admins included. */
    public boolean holdsEveryone() {
        return switching;
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

    /**
     * Returns the end of the player's move into the waiting room, already complete when no move is under way.
     * It completes whether the move succeeded or failed.
     */
    public CompletableFuture<Void> settled(final UUID player) {
        return inFlight.getOrDefault(player, CompletableFuture.completedFuture(null));
    }

    /** Returns whether an admin keeps the server stopped. */
    public boolean isHeld(final String server) {
        return server != null && held.contains(server);
    }

    /** Reads the update row and moves the players when the countdown has ended. It never throws. */
    public synchronized void check() {
        final Optional<UpdateRequest> running;
        final Optional<UpdateRequest> lastSwitch;
        try {
            held = heldServices(updates.holds());
            running = updates.running();
            lastSwitch = updates.lastStartedSwitch();
        } catch (final RuntimeException failure) {
            logger.warn("update row unreadable, nobody moved", failure);
            return;
        }
        final List<RunProgress.Step> steps = steps(running);
        followSwitch(lastSwitch.orElse(null), running, steps);

        final Set<String> next = notYetReplaced(imminent(running), steps);
        if (next.isEmpty()) {
            moving = Set.of();
            leg = null;
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
                        "waiting room moved by update, no standby room registered, nobody moved: waiting_room={}"
                                + " standby_room={}",
                        servers.limbo(),
                        servers.limboStandby());
            }
            moving = Set.of();
            leg = null;
            progress = OptionalInt.empty();
            return;
        }

        moving = next;
        final UpdateRequest run = running.orElseThrow();
        track(run, steps);
        if (startedByTheRun(started, run)) {
            // The proxy this one replaced moved the players already; the rooms may be gone by now.
            leg = null;
            return;
        }
        final Leg now = new Leg(run.id(), run.kind() == UpdateKind.SWITCH_MODE ? targetOf(run) : null);
        leg = now;
        evacuate(next, room, now);
    }

    /** Updates {@link #switching} from the newest switch run and runs {@link #whenReleased} when the hold ends. */
    private void followSwitch(
            final @Nullable UpdateRequest run,
            final Optional<UpdateRequest> running,
            final List<RunProgress.Step> runningSteps) {
        final boolean before = switching;
        final boolean now;
        if (run == null || (run.status() != UpdateStatus.RUNNING && run.status() != UpdateStatus.FAILED)) {
            now = ModeSwitch.holds(run, List.of(), "", clock.instant());
        } else {
            final boolean same = running.map(other -> other.id() == run.id()).orElse(false);
            final List<RunProgress.Step> steps = same ? runningSteps : steps(Optional.of(run));
            final String server = targetOf(run);
            now = server == null
                    ? ModeSwitch.moving(run, clock.instant())
                    : ModeSwitch.holds(run, steps, server, clock.instant());
        }
        switching = now;
        if (before && !now) {
            logger.info("mode switch over, waiting room released");
            try {
                whenReleased.run();
            } catch (final RuntimeException failure) {
                logger.warn("waiting room not released after mode switch", failure);
            }
        } else if (!before && now) {
            logger.info("mode switch holding players: run={}", run == null ? -1 : run.id());
        }
    }

    /** Returns the server that the switch run makes active, {@code null} while its request cannot be read. */
    private @Nullable String targetOf(final UpdateRequest run) {
        if (run.id() == targetRun) {
            return target;
        }
        final Optional<StewardRequest> request;
        try {
            request = updates.requestOf(run.id());
        } catch (final RuntimeException failure) {
            logger.warn("mode switch target unreadable: run={}", run.id(), failure);
            return null;
        }
        targetRun = run.id();
        target = request.filter(StewardRequest.SwitchMode.class::isInstance)
                .map(StewardRequest.SwitchMode.class::cast)
                .map(sw -> sw.target().server())
                .orElse(null);
        return target;
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

    /**
     * Returns the waiting room for the next try after a failed move into {@code failed}.
     * That is the other room when the run leaves it running, otherwise {@code failed} again, otherwise {@code null}.
     *
     * @param next the servers that the run stops
     * @param registered whether the proxy has a server of this name
     */
    static @Nullable String retryRoom(
            final String failed,
            final Set<String> next,
            final String limbo,
            final String standby,
            final Predicate<String> registered) {
        final String other = failed.equals(limbo) ? standby : limbo;
        for (final String room : List.of(other, failed)) {
            if (!next.contains(room) && registered.test(room)) {
                return room;
            }
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
            logger.warn("run steps unreadable, every moving service counted", failure);
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
            logger.warn("moved players not recorded: count={} from={}", players, from, failure);
        }
    }

    /** Moves every player on one of the servers to the waiting room. */
    private void evacuate(final Set<String> backends, final String waitingRoom, final Leg now) {
        final RegisteredServer limbo = proxy.getServer(waitingRoom).orElse(null);
        if (limbo == null) {
            logger.error("waiting room missing, nobody moved: services=\"{}\" waiting_room={}", backends, waitingRoom);
            return;
        }
        for (final Player player : proxy.getAllPlayers()) {
            final String on = serverOf(player);
            if (on != null && backends.contains(on)) {
                move(player, limbo, on, now);
            }
        }
    }

    /** Tries the move again in {@link #retryRoom} while the run still stops the player's server. */
    private synchronized void retry(final Player player, final String failed) {
        final Leg now = leg;
        final String on = serverOf(player);
        if (now == null || on == null || !player.isActive() || !moving.contains(on)) {
            return;
        }
        final String room = retryRoom(
                failed,
                moving,
                servers.limbo(),
                servers.limboStandby(),
                name -> proxy.getServer(name).isPresent());
        final RegisteredServer target =
                room == null ? null : proxy.getServer(room).orElse(null);
        if (target == null) {
            logger.warn("player not moved, no waiting room left: player={} from={}", player.getUsername(), on);
            return;
        }
        move(player, target, on, now);
    }

    /** Returns the server of the player, {@code null} while the player connects. */
    private static @Nullable String serverOf(final Player player) {
        return player.getCurrentServer()
                .map(connection -> connection.getServerInfo().getName())
                .orElse(null);
    }

    /**
     * Asks Velocity to move the player unless a move is already under way.
     * Velocity refuses a second request with its own red text, which {@code connect()} keeps from the player.
     *
     * @param from the server the run stops under the player
     */
    private void move(final Player player, final RegisteredServer room, final String from, final Leg now) {
        final CompletableFuture<Void> ended = new CompletableFuture<>();
        if (inFlight.putIfAbsent(player.getUniqueId(), ended) != null) {
            return;
        }
        final String name = room.getServerInfo().getName();
        try {
            final var _ = player.createConnectionRequest(room)
                    .connect()
                    .whenComplete((result, failure) ->
                            offNetwork(() -> moveEnded(player, name, from, now, result, failure, ended)));
        } catch (final RuntimeException failure) {
            moveEnded(player, name, from, now, null, failure, ended);
        }
    }

    /** Runs the work on the scheduler, away from Velocity's network thread, or here once the scheduler stopped. */
    private void offNetwork(final Runnable work) {
        try {
            scheduler.execute(work);
        } catch (final RejectedExecutionException stopped) {
            work.run();
        }
    }

    /** Counts a player who landed, and tries again after {@link #RETRY} for one who did not. */
    private void moveEnded(
            final Player player,
            final String room,
            final String from,
            final Leg now,
            final ConnectionRequestBuilder.@Nullable Result result,
            final @Nullable Throwable failure,
            final CompletableFuture<Void> ended) {
        try {
            if (failure == null && result != null && result.isSuccessful()) {
                landed(player, room, from, now);
                return;
            }
            if (failure != null) {
                logger.warn(
                        "player not moved into waiting room, retried: player={} waiting_room={} seconds={}",
                        player.getUsername(),
                        room,
                        RETRY.toSeconds(),
                        failure);
            } else {
                logger.info(
                        "player not moved into waiting room, retried: player={} waiting_room={} status={} seconds={}",
                        player.getUsername(),
                        room,
                        result == null ? null : result.getStatus(),
                        RETRY.toSeconds());
            }
            try {
                final var _ = scheduler.after(RETRY, () -> retry(player, room));
            } catch (final RejectedExecutionException stopped) {
                logger.warn("move retry not scheduled: player={}", player.getUsername(), stopped);
            }
        } finally {
            inFlight.remove(player.getUniqueId(), ended);
            ended.complete(null);
        }
    }

    /** Tells the player why the run moved them and counts the move, once per run. */
    private void landed(final Player player, final String room, final String from, final Leg now) {
        if (!homecoming.movedOut(now.run(), player, from, now.switchTo())) {
            return;
        }
        logger.info(
                "player moved into waiting room: player={} from={} waiting_room={}", player.getUsername(), from, room);
        moved(now.run(), from, room, 1);
    }
}
