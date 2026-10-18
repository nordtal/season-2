package eu.nordtal.season.stewardagent.run;

import static eu.nordtal.season.database.AdminTexts.TEXTS;

import eu.nordtal.season.common.time.Waiting;
import eu.nordtal.season.database.update.RunStep;
import eu.nordtal.season.database.update.UpdateReport.Note;
import eu.nordtal.season.database.update.UpdateReport.Step;
import eu.nordtal.season.internalapi.agent.AgentWire;
import eu.nordtal.season.internalapi.agent.RedeployResult;
import eu.nordtal.season.internalapi.agent.RuntimeResult;
import eu.nordtal.season.internalapi.agent.ServiceRuntime;
import eu.nordtal.season.internalapi.agent.Topology;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

/**
 * Starts the standbys a run needs, waits for players to leave, and stops the standbys again.
 *
 * Standbys come up before any warning, so an unhealthy one aborts a run that has touched nothing.
 */
@Slf4j
final class Choreography {

    /** The time the players get after the countdown to leave. */
    static final Duration EMPTY_CAP = Duration.ofSeconds(10);

    static final Duration EMPTY_POLL = Duration.ofSeconds(1);

    static final Duration STANDBY_HEALTHY_WITHIN = Duration.ofMinutes(3);

    static final Duration STANDBY_HEALTH_POLL = Duration.ofSeconds(5);

    /** How long a standby gets to empty out before it is stopped, longer than {@code StandbyReturn}'s own minute. */
    static final Duration STANDBY_DRAINS_WITHIN = Duration.ofSeconds(90);

    private final ContainerOps containers;
    private final Occupancy occupancy;
    private final Waiting clock;
    private final Steps record;

    private final Set<String> standing = new LinkedHashSet<>();

    private final Map<String, Steps.Step> comingUp = new LinkedHashMap<>();

    Choreography(final ContainerOps containers, final Occupancy occupancy, final Waiting clock) {
        this(containers, occupancy, clock, Steps.NONE);
    }

    Choreography(final ContainerOps containers, final Occupancy occupancy, final Waiting clock, final Steps record) {
        this.containers = containers;
        this.occupancy = occupancy;
        this.clock = clock;
        this.record = record;
    }

    /**
     * Which standbys a run over these services needs: one per moving service that has a standby.
     *
     * @param topology which service has which standby
     * @param moving the services this run stops
     */
    static List<String> standbysFor(final AgentWire.Topology topology, final Collection<String> moving) {
        return topology.services().stream()
                .filter(service -> service.standbyOf() != null && moving.contains(service.standbyOf()))
                .map(AgentWire.Service::name)
                .toList();
    }

    /**
     * Starts every standby this run needs and waits until each one is healthy, stopping them all on failure.
     *
     * @return a window that {@link Window#opened() opened}, or one with the note of the refusal
     */
    Window open(final Collection<String> moving) {
        final List<String> wanted = standbysFor(containers.topology(), moving);
        if (wanted.isEmpty()) {
            return new Window(List.of(), List.of());
        }

        for (final String standby : wanted) {
            // Never a pull over an image that is here: the standby must run its live service's, often one built here.
            final Steps.Step up = record.begin(RunStep.Kind.STANDBY_UP, standby);
            final RedeployResult made = containers.standby(standby);
            if (!made.triggered()) {
                up.failed(RunStep.Reason.REFUSED);
                return refuse(List.of(Note.failed(Step.STANDBY, TEXTS.report().standbyNotStarted(made.message()))
                        .on(standby)));
            }
            standing.add(standby);
            comingUp.put(standby, up);
        }

        final List<Note> late = waitHealthy(wanted);
        return late.isEmpty() ? new Window(List.copyOf(standing), List.of()) : refuse(late);
    }

    private Window refuse(final List<Note> why) {
        close();
        return new Window(List.of(), why);
    }

    /**
     * Waits until the standbys are healthy or the patience ends.
     *
     * @return one note per standby that did not come up
     */
    private List<Note> waitHealthy(final List<String> standbys) {
        final List<String> pending = new ArrayList<>(standbys);
        final Instant deadline = clock.now().plus(STANDBY_HEALTHY_WITHIN);
        while (true) {
            final RuntimeResult seen = containers.runtime();
            if (seen.reached()) {
                for (final String standby : List.copyOf(pending)) {
                    if (seen.service(standby).map(ServiceRuntime::isBack).orElse(false)) {
                        pending.remove(standby);
                        cameUp(standby, RunStep.Outcome.DONE, null);
                    }
                }
            }
            if (pending.isEmpty()) {
                return List.of();
            }
            if (!clock.now().isBefore(deadline)) {
                pending.forEach(standby -> cameUp(standby, RunStep.Outcome.FAILED, RunStep.Reason.NOT_HEALTHY));
                return pending.stream()
                        .map(standby -> Note.failed(
                                        Step.STANDBY,
                                        TEXTS.report()
                                                .standbysUnhealthy(
                                                        STANDBY_HEALTHY_WITHIN.toMinutes(),
                                                        UpdateRun.seen(seen, standby)
                                                                .said()))
                                .on(standby))
                        .toList();
            }
            if (!clock.sleep(STANDBY_HEALTH_POLL)) {
                pending.forEach(standby -> cameUp(standby, RunStep.Outcome.FAILED, RunStep.Reason.INTERRUPTED));
                return Note.failed(Step.STANDBY, TEXTS.report().standbysInterrupted())
                        .each(List.copyOf(pending));
            }
        }
    }

    private void cameUp(final String standby, final RunStep.Outcome outcome, final RunStep.@Nullable Reason reason) {
        final Steps.Step up = comingUp.remove(standby);
        if (up != null) {
            up.end(outcome, reason);
        }
    }

    /**
     * Waits until nobody is on the services about to stop, for at most {@link #EMPTY_CAP}.
     *
     * @param moving the services this run stops
     * @return how the wait ended, with the notes for the report when the run stops them with players on
     */
    Left waitUntilEmpty(final Collection<String> moving) {
        final List<String> watched =
                moving.stream().filter(this::canCarryPlayers).toList();
        if (watched.isEmpty()) {
            return Left.EMPTY;
        }

        final Instant deadline = clock.now().plus(EMPTY_CAP);
        while (true) {
            final Instant now = clock.now();
            final Map<String, Integer> occupied = new LinkedHashMap<>();
            final List<String> unknown = new ArrayList<>();
            for (final String service : watched) {
                final OptionalInt players = occupancy.on(service, now);
                if (players.isEmpty()) {
                    unknown.add(service);
                } else if (players.getAsInt() > 0) {
                    occupied.put(service, players.getAsInt());
                }
            }
            if (occupied.isEmpty() && unknown.isEmpty()) {
                return Left.EMPTY;
            }
            if (!now.isBefore(deadline)) {
                return new Left(
                        stoppedAnyway(occupied, unknown),
                        occupied.isEmpty() ? RunStep.Reason.PLAYERS_UNKNOWN : RunStep.Reason.PLAYERS_STAYED);
            }
            if (!clock.sleep(EMPTY_POLL)) {
                return new Left(
                        Note.warning(Step.PLAYERS, TEXTS.report().evacuationInterrupted())
                                .each(watched),
                        RunStep.Reason.INTERRUPTED);
            }
        }
    }

    /**
     * Returns the notes for a wait that hit the cap, which tell players still on a service from no answer.
     */
    static List<Note> stoppedAnyway(final Map<String, Integer> occupied, final List<String> unknown) {
        final List<Note> said = new ArrayList<>();
        for (final Map.Entry<String, Integer> on : occupied.entrySet()) {
            said.add(Note.warning(Step.PLAYERS, TEXTS.report().stoppedWithPlayers(on.getValue(), EMPTY_CAP.toSeconds()))
                    .on(on.getKey()));
        }
        said.addAll(Note.warning(Step.PLAYERS, TEXTS.report().playersUnknown(EMPTY_CAP.toSeconds()))
                .each(unknown));
        return List.copyOf(said);
    }

    private boolean canCarryPlayers(final String service) {
        return containers.topology().hasPlugins(service);
    }

    /**
     * Stops every standby this run started once it is empty, idempotently, at every exit.
     *
     * @return one note per standby, empty when this run opened no window
     */
    List<Note> close() {
        if (standing.isEmpty()) {
            return List.of();
        }
        final List<Note> said = new ArrayList<>();
        for (final String standby : List.copyOf(standing)) {
            final Steps.Step down = record.begin(RunStep.Kind.STANDBY_DOWN, standby);
            final Drained waited = waitDrained(standby);
            final RuntimeResult runtime = containers.runtime();
            final String id = runtime.reached()
                    ? runtime.service(standby).map(ServiceRuntime::containerId).orElse(null)
                    : null;
            if (id == null) {
                down.failed(RunStep.Reason.NO_CONTAINER);
                said.add(Note.failed(Step.STANDBY, TEXTS.report().standbyNoContainer())
                        .on(standby));
                standing.remove(standby);
                continue;
            }
            final RedeployResult stopped = containers.stop(id);
            standing.remove(standby);
            // The install mirrored its service's plugins into it as root while it ran.
            containers.handOverMounts(standby);
            final Note note;
            if (!stopped.triggered()) {
                down.failed(RunStep.Reason.REFUSED);
                note = Note.failed(Step.STANDBY, TEXTS.report().standbyNotStopped(stopped.message()));
            } else if (waited == null) {
                down.done();
                note = Note.done(Step.STANDBY, TEXTS.report().standbyStopped());
            } else if (waited.interrupted()) {
                down.warned(RunStep.Reason.INTERRUPTED);
                note = Note.warning(Step.STANDBY, TEXTS.report().standbyStoppedInterrupted(waited.players()));
            } else {
                down.warned(RunStep.Reason.PLAYERS_STAYED);
                note = Note.warning(
                        Step.STANDBY,
                        TEXTS.report().standbyStoppedWithPlayers(waited.players(), STANDBY_DRAINS_WITHIN.toSeconds()));
            }
            said.add(note.on(standby));
        }
        return said;
    }

    /**
     * Waits for one standby to empty.
     *
     * @return {@code null} when it emptied out, or what was still on it when the patience ran out
     */
    private @Nullable Drained waitDrained(final String standby) {
        final Instant deadline = clock.now().plus(STANDBY_DRAINS_WITHIN);
        while (true) {
            final Instant now = clock.now();
            final OptionalInt players = containers
                            .topology()
                            .standbyOf(Topology.PROXY)
                            .filter(standby::equals)
                            .isPresent()
                    ? occupancy.onStandbyProxy(now)
                    : occupancy.on(standby, now);
            if (players.isEmpty() || players.getAsInt() == 0) {
                // No answer counts as empty here, because the standby has stopped writing.
                return null;
            }
            if (!now.isBefore(deadline)) {
                return new Drained(players.getAsInt(), false);
            }
            if (!clock.sleep(EMPTY_POLL)) {
                return new Drained(players.getAsInt(), true);
            }
        }
    }

    /**
     * What {@link #open} decided.
     *
     * @param standbys the services started for this run
     * @param refusal why the run must not go on, empty when it may
     */
    record Window(List<String> standbys, List<Note> refusal) {

        boolean opened() {
            return refusal.isEmpty();
        }

        boolean isEmpty() {
            return standbys.isEmpty();
        }
    }

    /**
     * How the wait for the players ended.
     *
     * @param notes the report text, empty when everybody left in time
     * @param reason why the run stopped them anyway, {@code null} when they were gone
     */
    record Left(List<Note> notes, RunStep.@Nullable Reason reason) {

        static final Left EMPTY = new Left(List.of(), null);

        Left {
            notes = List.copyOf(notes);
        }

        RunStep.Outcome outcome() {
            return reason == null ? RunStep.Outcome.DONE : RunStep.Outcome.WARNING;
        }
    }

    private record Drained(long players, boolean interrupted) {}
}
