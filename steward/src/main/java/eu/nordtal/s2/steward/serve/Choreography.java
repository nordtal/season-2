package eu.nordtal.s2.steward.serve;

import eu.nordtal.s2.common.time.Waiting;
import eu.nordtal.s2.internalapi.agent.ContainerOps;
import eu.nordtal.s2.internalapi.agent.RedeployResult;
import eu.nordtal.s2.internalapi.agent.RuntimeResult;
import eu.nordtal.s2.internalapi.agent.ServiceRuntime;
import eu.nordtal.s2.steward.plan.Topology;
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
     * @param moving the services this run is going to stop
     * @return the compose service names to start first, in {@code SERVICES_WITH_STANDBY} order
     */
    static List<String> standbysFor(final Collection<String> moving) {
        return Topology.SERVICES_WITH_STANDBY.stream()
                .filter(moving::contains)
                .map(Topology::standbyOf)
                .toList();
    }

    /**
     * Starts every standby this run needs and waits until each one is healthy, stopping them all on failure.
     *
     * @return a window that {@link Window#opened() opened}, or one carrying the report sentence of a refused run
     */
    Window open(final Collection<String> moving) {
        final List<String> wanted = standbysFor(moving);
        if (wanted.isEmpty()) {
            return new Window(List.of(), null);
        }

        for (final String standby : wanted) {
            // recreate, not deploy: the standby must come up on its live service's image, often one built here.
            final RedeployResult made = containers.recreate(standby);
            if (!made.triggered()) {
                return refuse(standby + " could not be started: " + made.message());
            }
            standing.add(standby);
        }

        final String late = waitHealthy(wanted);
        return late == null ? new Window(List.copyOf(standing), null) : refuse(late);
    }

    private Window refuse(final String why) {
        close();
        return new Window(List.of(), why);
    }

    /**
     * Waits until these standbys are healthy or the patience runs out.
     *
     * @return {@code null} when every one of them came up, or the sentence naming those that did not
     */
    private @Nullable String waitHealthy(final List<String> standbys) {
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
                return String.join(", ", named) + " did not become healthy within " + STANDBY_HEALTHY_WITHIN.toMinutes()
                        + " minutes";
            }
            if (!clock.sleep(STANDBY_HEALTH_POLL)) {
                return "steward stopped while waiting for " + String.join(", ", pending);
            }
        }
    }

    /**
     * Waits until nobody is on the services about to stop, and gives up after {@link #EMPTY_CAP}.
     *
     * @param moving the services this run is going to stop
     * @return {@code null} when the run may stop them silently, or the sentence for the report
     */
    @Nullable
    String waitUntilEmpty(final Collection<String> moving) {
        final List<String> watched =
                moving.stream().filter(Choreography::canCarryPlayers).toList();
        if (watched.isEmpty()) {
            return null;
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
                return null;
            }
            if (!now.isBefore(deadline)) {
                return stoppedAnyway(occupied, unknown);
            }
            if (!clock.sleep(EMPTY_POLL)) {
                return "steward stopped while waiting for the players to be moved off " + String.join(", ", watched);
            }
        }
    }

    /** The sentence a run leaves when the cap ran out, telling players still on apart from no answer at all. */
    static String stoppedAnyway(final Map<String, Integer> occupied, final List<String> unknown) {
        final StringBuilder said = new StringBuilder();
        if (!occupied.isEmpty()) {
            final int total =
                    occupied.values().stream().mapToInt(Integer::intValue).sum();
            said.append("stopped with ")
                    .append(total)
                    .append(total == 1 ? " player" : " players")
                    .append(" still connected (");
            said.append(occupied.entrySet().stream()
                    .map(entry -> entry.getKey() + ": " + entry.getValue())
                    .reduce((a, b) -> a + ", " + b)
                    .orElse(""));
            said.append(") after waiting ").append(EMPTY_CAP.toSeconds()).append('s');
        }
        if (!unknown.isEmpty()) {
            if (!said.isEmpty()) {
                said.append(". ");
            }
            said.append("Nothing recent said how many players were on ")
                    .append(String.join(", ", unknown))
                    .append(", so the ")
                    .append(EMPTY_CAP.toSeconds())
                    .append("s wait ran out rather than ending: the proxy writes those counts, and"
                            + " a proxy that is not writing them is the one thing this wait cannot"
                            + " see past");
        }
        return said.toString();
    }

    /** Whether a player could be standing on this service at all; the others need no wait. */
    private static boolean canCarryPlayers(final String service) {
        return Topology.SERVICES.stream().anyMatch(one -> one.name().equals(service));
    }

    /**
     * Stops every standby this run started once it is empty, idempotently, at every exit.
     *
     * @return one sentence per standby, for the report; empty when this run opened no window
     */
    List<String> close() {
        if (standing.isEmpty()) {
            return List.of();
        }
        final List<String> said = new ArrayList<>();
        for (final String standby : List.copyOf(standing)) {
            final String waited = waitDrained(standby);
            final RuntimeResult runtime = containers.runtime();
            final String id = runtime.reached()
                    ? runtime.service(standby).map(ServiceRuntime::containerId).orElse(null)
                    : null;
            if (id == null) {
                said.add(standby + " was started for this run and could not be stopped again: the"
                        + " compose project has no container for it. It is still running.");
                standing.remove(standby);
                continue;
            }
            final RedeployResult stopped = containers.stop(id);
            standing.remove(standby);
            said.add(
                    stopped.triggered()
                            ? standby + " was started for this run and has been stopped again"
                                    + (waited == null ? "" : " - " + waited)
                            : standby + " was started for this run and could not be stopped again: " + stopped.message()
                                    + ". It is still running.");
        }
        return said;
    }

    /**
     * Waits for one standby to empty.
     *
     * @return {@code null} when it emptied out, or what was still on it when the patience ran out
     */
    private @Nullable String waitDrained(final String standby) {
        final Instant deadline = clock.now().plus(STANDBY_DRAINS_WITHIN);
        while (true) {
            final Instant now = clock.now();
            final OptionalInt players = Topology.standbyOf(Topology.PROXY).equals(standby)
                    ? occupancy.onStandbyProxy(now)
                    : occupancy.on(standby, now);
            if (players.isEmpty() || players.getAsInt() == 0) {
                // Empty is empty; "nothing said" counts as empty here only, since the standby has stopped writing.
                return null;
            }
            if (!now.isBefore(deadline)) {
                return "stopped with " + players.getAsInt() + " still on it after " + STANDBY_DRAINS_WITHIN.toSeconds()
                        + "s";
            }
            if (!clock.sleep(EMPTY_POLL)) {
                return "stopped while it still had " + players.getAsInt() + " on it";
            }
        }
    }

    /**
     * What {@link #open} decided.
     *
     * @param standbys the services started for this run, empty when it needed none
     * @param refusal why the run must not go on, or {@code null} when it may
     */
    record Window(List<String> standbys, @Nullable String refusal) {

        boolean opened() {
            return refusal == null;
        }

        boolean isEmpty() {
            return standbys.isEmpty();
        }
    }
}
