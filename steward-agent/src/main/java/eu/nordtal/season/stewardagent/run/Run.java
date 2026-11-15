package eu.nordtal.season.stewardagent.run;

import eu.nordtal.season.common.season.SeasonState;
import eu.nordtal.season.database.update.RunStage;
import eu.nordtal.season.database.update.RunStep;
import eu.nordtal.season.database.update.UpdateDirectory;
import eu.nordtal.season.database.update.UpdateRequest;
import eu.nordtal.season.internalapi.agent.AgentWire;
import eu.nordtal.season.internalapi.agent.ImageResult;
import eu.nordtal.season.internalapi.agent.RedeployResult;
import eu.nordtal.season.internalapi.agent.RuntimeResult;
import eu.nordtal.season.internalapi.agent.Topology;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

/**
 * The sequence every kind of run goes through. A kind only plans, see {@link Kinds}.
 *
 * Open the standbys, count down, evacuate, stop, carry out the kind's payload, start, verify, close and settle.
 */
@Slf4j
final class Run {

    private static final Duration RETURN_POLL = Duration.ofSeconds(1);

    private final Runner runner;
    private final UpdateRun steps;
    private final RuntimeResult runtime;
    private final Consumer<RunStage> progress;

    Run(final Runner runner, final UpdateRun steps, final RuntimeResult runtime, final Consumer<RunStage> progress) {
        this.runner = runner;
        this.steps = steps;
        this.runtime = runtime;
        this.progress = progress;
    }

    /**
     * The work between the stop and the start.
     *
     * Throwing {@link Abort} starts everything again and fails the run.
     */
    @FunctionalInterface
    interface Payload {

        Payload NONE = (steps, stopped) -> new Done(false);

        Done carryOut(UpdateRun steps, UpdateRun.Stopped stopped);
    }

    /**
     * What a payload left behind.
     *
     * @param failed whether something failed that no step of the run shows
     * @param afterwards what the run does once everything it stopped is back
     * @param leftDown services the run stopped and does not start again, because their data is incomplete
     */
    record Done(boolean failed, Runnable afterwards, List<String> leftDown) {

        Done {
            leftDown = List.copyOf(leftDown);
        }

        Done(final boolean failed) {
            this(failed, () -> {});
        }

        Done(final boolean failed, final Runnable afterwards) {
            this(failed, afterwards, List.of());
        }
    }

    /** Ends a payload early: everything stopped is started again and the run fails. */
    static final class Abort extends RuntimeException {

        Abort() {
            super(null, null, false, false);
        }
    }

    /**
     * The plan of a kind.
     *
     * @param moving the services the run stops, in that order
     * @param work the artefacts each service is given, whose versions its verify step names
     * @param announced whether the run counts down, so the proxy and the feeds warn first
     * @param stops whether the run stops what it plans, which a start does not
     * @param payload the work while the servers are down
     * @param startsAgain whether the run starts what it stopped, which only a take-down does not
     * @param alsoStarts services the run starts although it did not stop them, as the release of a hold does
     * @param images the services to recreate as they start, because their container is out of date
     * @param foreign the images nobody here builds, recreated once the rest is back and announced like a stop
     * @param refusesUnstopped whether a service that did not stop ends the run before its payload
     * @param remade services whose container is created again as they start
     * @param pulls whether recreating a container pulls its image first
     * @param countdown how long the players are warned when the run is announced
     */
    record Plan(
            List<String> moving,
            Map<String, List<RunStep.Change>> work,
            boolean announced,
            boolean stops,
            Payload payload,
            boolean startsAgain,
            List<String> alsoStarts,
            ImageResult images,
            List<String> foreign,
            boolean refusesUnstopped,
            Runner.Doubt doubt,
            boolean alreadyFailed,
            List<String> remade,
            boolean pulls,
            Duration countdown) {

        Plan {
            moving = List.copyOf(moving);
            work = Map.copyOf(work);
            alsoStarts = List.copyOf(alsoStarts);
            foreign = List.copyOf(foreign);
            remade = List.copyOf(remade);
        }

        static Plan of(
                final List<String> moving,
                final Payload payload,
                final boolean refusesUnstopped,
                final Runner.Doubt doubt) {
            return new Plan(
                    moving,
                    Map.of(),
                    true,
                    true,
                    payload,
                    true,
                    List.of(),
                    ImageResult.of(Map.of()),
                    List.of(),
                    refusesUnstopped,
                    doubt,
                    false,
                    List.of(),
                    true,
                    UpdateDirectory.UPDATE_COUNTDOWN);
        }

        Plan announced(final boolean warned) {
            return new Plan(
                    moving,
                    work,
                    warned,
                    stops,
                    payload,
                    startsAgain,
                    alsoStarts,
                    images,
                    foreign,
                    refusesUnstopped,
                    doubt,
                    alreadyFailed,
                    remade,
                    pulls,
                    countdown);
        }

        Plan stoppingNothing() {
            return new Plan(
                    moving,
                    work,
                    false,
                    false,
                    payload,
                    startsAgain,
                    alsoStarts,
                    images,
                    foreign,
                    refusesUnstopped,
                    doubt,
                    alreadyFailed,
                    remade,
                    pulls,
                    countdown);
        }

        Plan leavingThemDown() {
            return new Plan(
                    moving,
                    work,
                    announced,
                    stops,
                    payload,
                    false,
                    alsoStarts,
                    images,
                    foreign,
                    refusesUnstopped,
                    doubt,
                    alreadyFailed,
                    remade,
                    pulls,
                    countdown);
        }

        Plan alsoStarting(final List<String> services) {
            return new Plan(
                    moving,
                    work,
                    announced,
                    stops,
                    payload,
                    startsAgain,
                    services,
                    images,
                    foreign,
                    refusesUnstopped,
                    doubt,
                    alreadyFailed,
                    remade,
                    pulls,
                    countdown);
        }

        Plan renewing(
                final Map<String, List<RunStep.Change>> given,
                final ImageResult newer,
                final List<String> renewed,
                final boolean failed) {
            return new Plan(
                    moving,
                    given,
                    announced,
                    stops,
                    payload,
                    startsAgain,
                    alsoStarts,
                    newer,
                    renewed,
                    refusesUnstopped,
                    doubt,
                    failed,
                    remade,
                    pulls,
                    countdown);
        }

        /**
         * Makes the containers of these services again: ours as they start, the foreign ones once the rest is back.
         *
         * @param pulling whether each image is pulled first
         */
        Plan remaking(final List<String> ours, final List<String> theirs, final boolean pulling) {
            return new Plan(
                    moving,
                    work,
                    announced,
                    stops,
                    payload,
                    startsAgain,
                    alsoStarts,
                    images,
                    theirs,
                    refusesUnstopped,
                    doubt,
                    alreadyFailed,
                    ours,
                    pulling,
                    countdown);
        }

        /** Warns the players for {@code length} instead of an update's countdown, zero for a mode switch. */
        Plan countingDown(final Duration length) {
            return new Plan(
                    moving,
                    work,
                    announced,
                    stops,
                    payload,
                    startsAgain,
                    alsoStarts,
                    images,
                    foreign,
                    refusesUnstopped,
                    doubt,
                    alreadyFailed,
                    remade,
                    pulls,
                    length);
        }

        /**
         * Returns what the countdown names: every stopped service and every recreated foreign image.
         */
        List<String> named() {
            final Set<String> named = new LinkedHashSet<>(moving);
            named.addAll(foreign);
            return List.copyOf(named);
        }
    }

    /** Carries the plan out. It never throws, so every stop ends in a start. */
    Outcome carryOut(final UpdateRequest request, final Plan plan) {
        final Choreography choreography =
                new Choreography(runner.containers, runner.occupancy(), runner.waiting, steps.record);
        final List<String> moving = plan.stops() ? plan.moving() : List.of();
        if (!choreography.open(moving).opened()) {
            return Outcome.failed();
        }
        try {
            choreography.noteLeaving(moving);
            final Steps.Step players = plan.announced()
                    ? runner.countDown(request.id(), plan.countdown(), plan.named(), progress, steps.record)
                    : null;
            if (plan.announced() && players == null) {
                return Runner.cancelled();
            }

            // The players get ten seconds after the countdown, then the run stops the services.
            final Choreography.Left left = choreography.waitUntilEmpty(moving);
            if (players != null) {
                players.end(left.outcome(), left.reason());
            } else if (left.reason() != null) {
                steps.record.ended(RunStep.Kind.PLAYERS_MOVED, null, left.outcome(), left.reason());
            }

            final UpdateRun.Stopped stopped =
                    plan.stops() ? steps.stop(moving, runtime) : new UpdateRun.Stopped(List.of(), runtime);
            if (plan.refusesUnstopped() && !stopped.services().containsAll(moving)) {
                return startAgain(stopped);
            }

            final Done done;
            try {
                done = plan.payload().carryOut(steps, stopped);
            } catch (final Abort abort) {
                // Started again before the run settles, so a failed payload never leaves the network stopped.
                return startAgain(stopped);
            }

            return finish(plan, stopped, done, choreography, players);
        } finally {
            choreography.close();
        }
    }

    private Outcome startAgain(final UpdateRun.Stopped stopped) {
        steps.start(stopped);
        steps.verify(stopped.services(), runner.waiting);
        return Outcome.failed();
    }

    /**
     * Ends {@code returning} on whether every player the run moved out came back, waiting for the last of them.
     * Players moved off a held service stay in the waiting room on purpose, so those moves are not counted.
     *
     * @param leaving the service each player was seen on before it stopped, the only players a waiting room may owe
     */
    private void countBack(final Steps.Step returning, final boolean counted, final Map<UUID, String> leaving) {
        if (!counted) {
            // A run from an older schema wrote no moves, so nothing says who should come back.
            returning.warned(RunStep.Reason.PLAYERS_UNKNOWN);
            return;
        }
        final Optional<Set<String>> holds = holds();
        if (holds.isEmpty()) {
            returning.warned(RunStep.Reason.PLAYERS_UNKNOWN);
            return;
        }
        final Set<String> held = holds.get();
        final Instant started = runner.waiting.now();
        Instant progressed = started;
        int owedBefore = Integer.MAX_VALUE;
        AgentWire.@Nullable Topology topology = null;
        while (true) {
            final Optional<List<RunStep.Move>> moves = steps.record.moves();
            if (moves.isEmpty()) {
                returning.warned(RunStep.Reason.PLAYERS_UNKNOWN);
                return;
            }
            final List<RunStep.Move> missing = moves.get().stream()
                    .filter(move -> !held.contains(move.from()) && move.returned() < move.moved())
                    .toList();
            final int owed = missing.stream()
                    .mapToInt(move -> move.moved() - move.returned())
                    .sum();
            final Instant now = runner.waiting.now();
            final Duration elapsed = Duration.between(started, now);
            if (owed < owedBefore) {
                progressed = now;
                owedBefore = owed;
            }
            // The waiting rooms are read only once the cap has passed, since only then do they decide.
            final boolean past = elapsed.compareTo(PlayersBack.CAP) >= 0;
            if (past && owed > 0 && topology == null) {
                topology = topology();
            }
            final int waiting = topology != null && owed > 0 ? waitingFor(topology, leaving, missing, now) : 0;
            final boolean maintenance = past && owed > 0 && maintenance();
            final PlayersBack.Verdict verdict =
                    PlayersBack.judge(owed, waiting, maintenance, elapsed, Duration.between(progressed, now));
            if (ends(returning, verdict, missing, waiting)) {
                return;
            }
        }
    }

    /** Ends {@code returning} as the verdict says, or sleeps until the next look; returns whether it ended. */
    private boolean ends(
            final Steps.Step returning,
            final PlayersBack.Verdict verdict,
            final List<RunStep.Move> missing,
            final int waiting) {
        return switch (verdict) {
            case DONE -> {
                if (!missing.isEmpty()) {
                    log.info("players back counted, the rest wait for maintenance: moves={}", missing);
                }
                returning.done();
                yield true;
            }
            case LOST -> {
                log.warn("fewer players came back than moved out: moves={} waiting={}", missing, waiting);
                returning.warned(RunStep.Reason.PLAYERS_LOST);
                yield true;
            }
            case WAIT -> {
                if (runner.waiting.sleep(RETURN_POLL)) {
                    yield false;
                }
                returning.warned(RunStep.Reason.INTERRUPTED);
                yield true;
            }
        };
    }

    /** Returns the held services, empty when they cannot be read. */
    private Optional<Set<String>> holds() {
        try {
            return Optional.of(Set.copyOf(runner.held()));
        } catch (final RuntimeException unread) {
            log.warn("service holds not read, players back not counted", unread);
            return Optional.empty();
        }
    }

    /**
     * Returns how many of the players who left wait in the rooms the missing players were moved into.
     *
     * A standby's players wait in its live room once it stops, so that room counts too.
     */
    private int waitingFor(
            final AgentWire.Topology topology,
            final Map<UUID, String> leaving,
            final List<RunStep.Move> missing,
            final Instant now) {
        final Optional<String> proxyStandby = topology.standbyOf(Topology.PROXY);
        final Set<String> rooms = new LinkedHashSet<>();
        final Set<String> from = new LinkedHashSet<>();
        for (final RunStep.Move move : missing) {
            from.add(move.from());
            rooms.add(move.to());
            topology.services().stream()
                    .filter(service -> service.name().equals(move.to()))
                    .map(AgentWire.Service::standbyOf)
                    .filter(live -> live != null && !Topology.PROXY.equals(live))
                    .forEach(rooms::add);
        }
        // The standby proxy writes no roster, only how many it holds.
        int waiting = proxyStandby.filter(rooms::contains).isPresent()
                ? runner.occupancy().onStandbyProxy(now).orElse(0)
                : 0;
        for (final Map.Entry<UUID, String> player :
                runner.occupancy().roster(now).entrySet()) {
            // A player of a held service waits on purpose, so only a missing move's own players count.
            if (from.contains(leaving.get(player.getKey())) && rooms.contains(player.getValue())) {
                waiting++;
            }
        }
        return waiting;
    }

    /** Returns the topology, an empty one when it cannot be read, so no standby's live room counts. */
    private AgentWire.Topology topology() {
        try {
            return runner.topology();
        } catch (final RuntimeException unread) {
            log.warn("topology not read, standby rooms not counted as waiting", unread);
            return new AgentWire.Topology(List.of(), List.of());
        }
    }

    /** Returns whether maintenance is on, false when its row cannot be read. */
    private boolean maintenance() {
        try {
            final SeasonState state = runner.season().state();
            // A missing row reads as maintenance on, which would hide a real loss.
            return !SeasonState.UNREAD.equals(state) && state.maintenance();
        } catch (final RuntimeException unread) {
            log.warn("maintenance not read, players back counted without it", unread);
            return false;
        }
    }

    private Outcome finish(
            final Plan plan,
            final UpdateRun.Stopped stopped,
            final Done done,
            final Choreography choreography,
            final Steps.@Nullable Step moved) {
        if (plan.startsAgain()) {
            final List<String> starting = new ArrayList<>(stopped.services());
            plan.alsoStarts().stream()
                    .filter(service -> !starting.contains(service))
                    .forEach(starting::add);
            starting.removeAll(done.leftDown());
            final UpdateRun.Stopped back = new UpdateRun.Stopped(List.copyOf(starting), runtime);
            steps.start(back, plan.images(), plan.remade(), plan.pulls());
            steps.verify(starting, runner.waiting, plan.work());
        }

        // The proxy counts the players back. The standbys give theirs back as they close.
        final Steps.Step returning = moved != null ? steps.record.begin(RunStep.Kind.PLAYERS_BACK) : null;
        // The standbys close first, because caddy publishes their ports.
        choreography.close();
        if (moved != null && returning != null) {
            countBack(returning, moved.written(), choreography.leaving());
        }
        // This runs once the Minecraft services are healthy, with postgres last.
        ForeignImages.renewForeign(runner.containers, steps, plan.foreign(), plan.pulls(), runner.waiting);
        done.afterwards().run();
        // Last of all, since the agent it creates leaves the row alone.
        if (runner.oneShot) {
            renewAgent();
        }
        return Outcome.at(Runner.settle(
                steps.record, steps.unverifiedStops(), plan.alreadyFailed() || done.failed(), plan.doubt()));
    }

    /**
     * Creates the long-running steward-agent at the release of this one-shot and waits for its health.
     */
    private void renewAgent() {
        final Steps.Step install = steps.record
                .begin(RunStep.Kind.INSTALL, AgentWire.SERVICE)
                .change(UpdateRun.IMAGE, null, null, RunStep.Image.PULLED);
        final RedeployResult result = runner.containers.renewAgent();
        UpdateRun.endBy(install, result);
        if (result.triggered()) {
            steps.verify(List.of(AgentWire.SERVICE), runner.waiting);
        }
    }
}
