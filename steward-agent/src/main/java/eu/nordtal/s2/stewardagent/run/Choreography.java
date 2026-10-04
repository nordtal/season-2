package eu.nordtal.s2.stewardagent.run;

import static eu.nordtal.s2.database.AdminTexts.TEXTS;

import eu.nordtal.s2.common.time.Waiting;
import eu.nordtal.s2.internalapi.agent.AgentWire;
import eu.nordtal.s2.internalapi.agent.RedeployResult;
import eu.nordtal.s2.internalapi.agent.RuntimeResult;
import eu.nordtal.s2.internalapi.agent.ServiceRuntime;
import eu.nordtal.s2.internalapi.agent.Topology;
import eu.nordtal.s2.messages.MessageRef;
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

    /** How long the players get, after the countdown, to be somewhere else. */
    static final Duration EMPTY_CAP = Duration.ofSeconds(10);

    /** How often the counts are re-read while waiting, one indexed read per second. */
    static final Duration EMPTY_POLL = Duration.ofSeconds(1);

    /** How long a standby gets to become healthy before the run is abandoned, while nothing is down yet. */
    static final Duration STANDBY_HEALTHY_WITHIN = Duration.ofMinutes(3);

    /** How often the runtime is re-read while waiting for a standby. */
    static final Duration STANDBY_HEALTH_POLL = Duration.ofSeconds(5);

    /** How long a standby gets to empty out before it is stopped, longer than {@code StandbyReturn}'s own minute. */
    static final Duration STANDBY_DRAINS_WITHIN = Duration.ofSeconds(90);

    private final ContainerOps containers;
    private final Occupancy occupancy;
    private final Waiting clock;

    /** What this run has started and not yet stopped, so {@link #close} can be called twice. */
    private final Set<String> standing = new LinkedHashSet<>();

    Choreography(final ContainerOps containers, final Occupancy occupancy, final Waiting clock) {
        this.containers = containers;
        this.occupancy = occupancy;
        this.clock = clock;
    }

    /**
     * Which standbys a run over these services needs: one per moving service that has a standby.
     *
     * @param topology which service has which standby
     * @param moving the services this run is going to stop
     * @return the compose service names to start first, in compose.yml's order
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
     * @return a window that {@link Window#opened() opened}, or one carrying the report note of a refused run
     */
    Window open(final Collection<String> moving) {
        final List<String> wanted = standbysFor(containers.topology(), moving);
        if (wanted.isEmpty()) {
            return new Window(List.of(), null);
        }

        for (final String standby : wanted) {
            // Never a pull over an image that is here: the standby must run its live service's, often one built here.
            final RedeployResult made = containers.standby(standby);
            if (!made.triggered()) {
                return refuse(TEXTS.report().standbyNotStarted(standby, made.message()));
            }
            standing.add(standby);
        }

        final MessageRef late = waitHealthy(wanted);
        return late == null ? new Window(List.copyOf(standing), null) : refuse(late);
    }

    private Window refuse(final MessageRef why) {
        close();
        return new Window(List.of(), why);
    }

    /**
     * Waits until these standbys are healthy or the patience runs out.
     *
     * @return {@code null} when every one of them came up, or the note naming those that did not
     */
    private @Nullable MessageRef waitHealthy(final List<String> standbys) {
        final List<String> pending = new ArrayList<>(standbys);
        final Instant deadline = clock.now().plus(STANDBY_HEALTHY_WITHIN);
        while (true) {
            // One snapshot per iteration, asked about every pending service.
            final RuntimeResult seen = containers.runtime();
            if (seen.reached()) {
                pending.removeIf(standby ->
                        seen.service(standby).map(ServiceRuntime::isBack).orElse(false));
            }
            if (pending.isEmpty()) {
                return null;
            }
            if (!clock.now().isBefore(deadline)) {
                final List<String> named = new ArrayList<>();
                for (final String standby : pending) {
                    final String state = seen.reached()
                            ? seen.service(standby)
                                    .map(ServiceRuntime::describe)
                                    .orElse("no container for it in the project")
                            : "the container runtime could not be read: " + seen.message();
                    named.add(standby + " (" + state + ")");
                }
                return TEXTS.report().standbysUnhealthy(named, STANDBY_HEALTHY_WITHIN.toMinutes());
            }
            if (!clock.sleep(STANDBY_HEALTH_POLL)) {
                return TEXTS.report().standbysInterrupted(List.copyOf(pending));
            }
        }
    }

    /**
     * Waits until nobody is on the services about to stop, and gives up after {@link #EMPTY_CAP}.
     *
     * @param moving the services this run is going to stop
     * @return nothing when the run may stop them silently, or the notes for the report
     */
    List<MessageRef> waitUntilEmpty(final Collection<String> moving) {
        final List<String> watched =
                moving.stream().filter(this::canCarryPlayers).toList();
        if (watched.isEmpty()) {
            return List.of();
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
                return List.of();
            }
            if (!now.isBefore(deadline)) {
                return stoppedAnyway(occupied, unknown);
            }
            if (!clock.sleep(EMPTY_POLL)) {
                return List.of(TEXTS.report().evacuationInterrupted(watched));
            }
        }
    }

    /** The notes a run leaves when the cap ran out, telling players still on apart from no answer at all. */
    static List<MessageRef> stoppedAnyway(final Map<String, Integer> occupied, final List<String> unknown) {
        final List<MessageRef> said = new ArrayList<>();
        if (!occupied.isEmpty()) {
            final int total =
                    occupied.values().stream().mapToInt(Integer::intValue).sum();
            said.add(TEXTS.report()
                    .stoppedWithPlayers(
                            total,
                            occupied.entrySet().stream()
                                    .map(entry -> entry.getKey() + ": " + entry.getValue())
                                    .toList(),
                            EMPTY_CAP.toSeconds()));
        }
        if (!unknown.isEmpty()) {
            said.add(TEXTS.report().playersUnknown(unknown, EMPTY_CAP.toSeconds()));
        }
        return List.copyOf(said);
    }

    /** Whether a player could be standing on this service at all; the others need no wait. */
    private boolean canCarryPlayers(final String service) {
        return containers.topology().hasPlugins(service);
    }

    /**
     * Stops every standby this run started once it is empty, idempotently, at every exit.
     *
     * @return one note per standby, for the report; empty when this run opened no window
     */
    List<MessageRef> close() {
        if (standing.isEmpty()) {
            return List.of();
        }
        final List<MessageRef> said = new ArrayList<>();
        for (final String standby : List.copyOf(standing)) {
            final Drained waited = waitDrained(standby);
            final RuntimeResult runtime = containers.runtime();
            final String id = runtime.reached()
                    ? runtime.service(standby).map(ServiceRuntime::containerId).orElse(null)
                    : null;
            if (id == null) {
                said.add(TEXTS.report().standbyNoContainer(standby));
                standing.remove(standby);
                continue;
            }
            final RedeployResult stopped = containers.stop(id);
            standing.remove(standby);
            if (!stopped.triggered()) {
                said.add(TEXTS.report().standbyNotStopped(standby, stopped.message()));
            } else if (waited == null) {
                said.add(TEXTS.report().standbyStopped(standby));
            } else if (waited.interrupted()) {
                said.add(TEXTS.report().standbyStoppedInterrupted(standby, waited.players()));
            } else {
                said.add(TEXTS.report()
                        .standbyStoppedWithPlayers(standby, waited.players(), STANDBY_DRAINS_WITHIN.toSeconds()));
            }
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
                // Empty is empty; "nothing said" counts as empty here only, since the standby has stopped writing.
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
     * @param standbys the services started for this run, empty when it needed none
     * @param refusal why the run must not go on, or {@code null} when it may
     */
    record Window(List<String> standbys, @Nullable MessageRef refusal) {

        boolean opened() {
            return refusal == null;
        }

        boolean isEmpty() {
            return standbys.isEmpty();
        }
    }

    /** Who was still on a standby when it was stopped anyway, and whether the agent itself stopped the wait. */
    private record Drained(long players, boolean interrupted) {}
}
