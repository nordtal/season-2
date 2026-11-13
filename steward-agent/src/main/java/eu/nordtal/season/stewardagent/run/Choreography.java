package eu.nordtal.season.stewardagent.run;

import eu.nordtal.season.common.time.Waiting;
import eu.nordtal.season.database.update.RunStep;
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
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

/**
 * Starts the standbys a run needs, waits for players to leave, and stops the standbys again.
 *
 * Standbys come up before any warning, so one that is unhealthy or speaks another protocol aborts an untouched run.
 */
@Slf4j
final class Choreography {

    /** The time the players get after the countdown to leave, which covers a move that waits for the resource pack. */
    static final Duration EMPTY_CAP = Duration.ofSeconds(60);

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
        this(containers, occupancy, clock, Steps.none());
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
     * @return a window that {@link Window#opened() opened}, or a refused one whose steps say why
     */
    Window open(final Collection<String> moving) {
        final List<String> wanted = standbysFor(containers.topology(), moving);
        if (wanted.isEmpty()) {
            return new Window(List.of(), true);
        }

        for (final String standby : wanted) {
            // Never a pull over an image that is here: the standby must run its live service's, often one built here.
            final Steps.Step up = record.begin(RunStep.Kind.STANDBY_UP, standby);
            final RedeployResult made = containers.standby(standby);
            if (!made.triggered()) {
                up.failed(RunStep.Reason.REFUSED);
                return refuse();
            }
            standing.add(standby);
            comingUp.put(standby, up);
        }

        return waitHealthy(wanted) ? new Window(List.copyOf(standing), true) : refuse();
    }

    private Window refuse() {
        close();
        return new Window(List.of(), false);
    }

    /**
     * Waits until the standbys are healthy and answer like their live services, or the patience ends.
     *
     * @return whether every standby came up
     */
    private boolean waitHealthy(final List<String> standbys) {
        final List<String> pending = new ArrayList<>(standbys);
        final Instant deadline = clock.now().plus(STANDBY_HEALTHY_WITHIN);
        boolean mismatched = false;
        final Set<String> silent = new LinkedHashSet<>();
        while (true) {
            final RuntimeResult seen = containers.runtime();
            if (seen.reached()) {
                for (final String standby : List.copyOf(pending)) {
                    if (!seen.service(standby).map(ServiceRuntime::isBack).orElse(false)) {
                        continue;
                    }
                    final Answer answer = answersLikeItsService(standby, seen);
                    if (answer == Answer.SILENT) {
                        // Healthy but not answering yet; the patience below decides.
                        silent.add(standby);
                        continue;
                    }
                    silent.remove(standby);
                    pending.remove(standby);
                    if (answer == Answer.OTHER_PROTOCOL) {
                        mismatched = true;
                        cameUp(standby, RunStep.Outcome.FAILED, RunStep.Reason.PROTOCOL_MISMATCH);
                    } else {
                        cameUp(standby, RunStep.Outcome.DONE, null);
                    }
                }
            }
            if (pending.isEmpty()) {
                return !mismatched;
            }
            if (!clock.now().isBefore(deadline)) {
                if (!silent.isEmpty()) {
                    log.warn("standby healthy but answered no status ping in time: standbys={}", silent);
                }
                pending.forEach(standby -> cameUp(standby, RunStep.Outcome.FAILED, RunStep.Reason.NOT_HEALTHY));
                return false;
            }
            if (!clock.sleep(STANDBY_HEALTH_POLL)) {
                pending.forEach(standby -> cameUp(standby, RunStep.Outcome.FAILED, RunStep.Reason.INTERRUPTED));
                return false;
            }
        }
    }

    /** How a standby answered a status ping, held against its live service's answer. */
    private enum Answer {
        SAME_PROTOCOL,
        OTHER_PROTOCOL,
        SILENT
    }

    /**
     * Pings the standby and its live service. A client the live service takes must be able to land on the standby.
     *
     * A live service that does not answer, such as one that is stopped, leaves the standby's health as the check.
     */
    private Answer answersLikeItsService(final String standby, final RuntimeResult seen) {
        final OptionalInt own = ping(standby, seen);
        if (own.isEmpty()) {
            return Answer.SILENT;
        }
        final String live = containers.topology().services().stream()
                .filter(service -> service.name().equals(standby))
                .map(AgentWire.Service::standbyOf)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
        final OptionalInt theirs = live == null ? OptionalInt.empty() : ping(live, seen);
        if (theirs.isEmpty()) {
            log.info(
                    "standby protocol not compared, its service did not answer: standby={} protocol={}",
                    standby,
                    own.getAsInt());
            return Answer.SAME_PROTOCOL;
        }
        if (own.getAsInt() != theirs.getAsInt()) {
            log.warn(
                    "standby answers another protocol than its service: standby={} protocol={} service={} expected={}",
                    standby,
                    own.getAsInt(),
                    live,
                    theirs.getAsInt());
            return Answer.OTHER_PROTOCOL;
        }
        return Answer.SAME_PROTOCOL;
    }

    private OptionalInt ping(final String service, final RuntimeResult seen) {
        return seen.service(service)
                .filter(found -> found.containerId() != null && "running".equalsIgnoreCase(found.status()))
                .map(found -> containers.protocol(Objects.requireNonNull(found.containerId())))
                .orElseGet(OptionalInt::empty);
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
     * @return how the wait ended
     */
    Left waitUntilEmpty(final Collection<String> moving) {
        final List<String> watched =
                moving.stream().filter(this::canCarryPlayers).toList();
        if (watched.isEmpty()) {
            return Left.EMPTY;
        }

        final Instant started = clock.now();
        final Instant deadline = started.plus(EMPTY_CAP);
        while (true) {
            final Instant now = clock.now();
            final Map<String, Integer> occupied = new LinkedHashMap<>();
            final List<String> unknown = new ArrayList<>();
            for (final String service : watched) {
                // A row from before the countdown ended predates the moves; only a later count can end the wait.
                final OptionalInt players = occupancy.on(service, now, started);
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
                log.warn("wait for empty servers ended with players on: occupied={} uncounted={}", occupied, unknown);
                return new Left(occupied.isEmpty() ? RunStep.Reason.PLAYERS_UNKNOWN : RunStep.Reason.PLAYERS_STAYED);
            }
            if (!clock.sleep(EMPTY_POLL)) {
                return new Left(RunStep.Reason.INTERRUPTED);
            }
        }
    }

    private boolean canCarryPlayers(final String service) {
        return containers.topology().hasPlugins(service);
    }

    /**
     * Stops every standby this run started once it is empty, idempotently, at every exit.
     *
     * Its steps say how each went.
     */
    void close() {
        for (final String standby : List.copyOf(standing)) {
            final Steps.Step down = record.begin(RunStep.Kind.STANDBY_DOWN, standby);
            final Drained waited = waitDrained(standby);
            final RuntimeResult runtime = containers.runtime();
            final String id = runtime.reached()
                    ? runtime.service(standby).map(ServiceRuntime::containerId).orElse(null)
                    : null;
            if (id == null) {
                down.failed(RunStep.Reason.NO_CONTAINER);
                standing.remove(standby);
                continue;
            }
            final RedeployResult stopped = containers.stop(id);
            standing.remove(standby);
            // The install mirrored its service's plugins into it as root while it ran.
            containers.handOverMounts(standby);
            if (!stopped.triggered()) {
                down.failed(RunStep.Reason.REFUSED);
            } else if (waited == null) {
                down.done();
            } else if (waited.interrupted()) {
                down.warned(RunStep.Reason.INTERRUPTED);
            } else {
                log.warn("standby stopped with players on: service={} count={}", standby, waited.players());
                down.warned(RunStep.Reason.PLAYERS_STAYED);
            }
        }
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
                    : occupancy.on(standby, now, Instant.MIN);
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
     * @param opened whether the run may go on
     */
    record Window(List<String> standbys, boolean opened) {

        boolean isEmpty() {
            return standbys.isEmpty();
        }
    }

    /**
     * How the wait for the players ended.
     *
     * @param reason why the run stopped them anyway, {@code null} when they were gone
     */
    record Left(RunStep.@Nullable Reason reason) {

        static final Left EMPTY = new Left(null);

        RunStep.Outcome outcome() {
            return reason == null ? RunStep.Outcome.DONE : RunStep.Outcome.WARNING;
        }
    }

    private record Drained(long players, boolean interrupted) {}
}
