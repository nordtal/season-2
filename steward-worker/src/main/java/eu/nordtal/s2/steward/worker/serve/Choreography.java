package eu.nordtal.s2.steward.worker.serve;

import eu.nordtal.s2.steward.worker.ops.ContainerOps;
import eu.nordtal.s2.steward.worker.ops.RedeployResult;
import eu.nordtal.s2.steward.worker.ops.RuntimeResult;
import eu.nordtal.s2.steward.worker.ops.ServiceRuntime;
import eu.nordtal.s2.steward.worker.plan.Topology;
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
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The run's stage directions: which standbys it needs, when the players are gone, and when the
 * standbys may be stopped again (season-2-ops/122).
 *
 * <h2>steward-worker directs; the plugins perform</h2>
 * Every piece of this already existed and none of it was driven. {@code ProxySwap} parks the whole
 * network on {@code proxy-standby} the moment it sees a run about to stop the proxy;
 * {@code Evacuation} moves players off a backend into {@code limbo} or {@code limbo-standby};
 * {@code StandbyReturn} brings everybody home on its own. What none of them can do is <b>start the
 * standby</b>: it lives in a compose profile no ordinary selection carries, and until this class
 * existed the honest outcome of every one of those mechanisms was the one {@code ProxySwap} logs -
 * "the standby has to be running BEFORE the run reaches this proxy", and nothing ever started it.
 * That is Till's finding in season-2-ops/123 in as many words: <i>a recreate of the limbo does not
 * currently start the standby first; the same for the proxy.</i>
 *
 * <h2>The order, and why the standby comes before the warning</h2>
 * The standbys are started and waited for <b>first</b>, before a single player is told anything and
 * before anything is stopped. A standby that does not become healthy therefore aborts a run that
 * has touched nothing at all, which is Till's decision of 2026-09-19 and the real reason this order
 * is worth its extra minute: the alternative is a countdown that warns everybody and then discovers
 * it has nowhere to put them.
 *
 * <h2>Waiting for the players, with a cap and a closed door</h2>
 * Till, 2026-09-20: wait after the countdown until the players are gone, hold the door shut while
 * waiting so nobody new arrives, and stop anyway after ten seconds.
 *
 * <ul>
 *   <li><b>The waiting</b> is {@link #waitUntilEmpty}. It reads what the proxy writes rather than
 *       asking it, for the reason {@code OnlineDirectory} gives.</li>
 *   <li><b>The door</b> needs nothing here, and that is worth writing down rather than leaving as a
 *       gap: every login in this network lands in the waiting room first, and
 *       {@code LimboHold#reason} already refuses to release anybody whose destination an update run
 *       is moving. So for exactly the span in which {@code Evacuation} reports that service as
 *       moving - which starts eight seconds before the stop and lasts the whole run - a new player
 *       sits in the waiting room under "Update in progress" instead of walking onto a server that
 *       is about to go down. A second, worker-side blocker would be a second answer to a question
 *       that already has one.</li>
 *   <li><b>The cap</b> is ten seconds and it is a number, not a feeling: a run that waited on a
 *       hung transfer would never end. What it must never be is silent - a stop that happens with
 *       somebody still connected is a line in the report, because that line is the only thing that
 *       says where to look next time.</li>
 * </ul>
 */
@Slf4j
final class Choreography {

    /** How long the players get, after the countdown, to be somewhere else (Till, 2026-09-20). */
    static final Duration EMPTY_CAP = Duration.ofSeconds(10);

    /** How often the counts are re-read while waiting. One indexed read per second. */
    static final Duration EMPTY_POLL = Duration.ofSeconds(1);

    /**
     * How long a standby gets to become healthy before the run is abandoned.
     *
     * <p>Three minutes rather than {@code UpdateRun.HEALTH_PATIENCE}'s five. Nothing is down yet and
     * nobody has been warned, so the cost of giving up is one report and no outage - while the cost
     * of waiting is that the run has not started. A limbo is a Paper server with a tiny flat world
     * and was measured coming up in well under a minute on this host.</p>
     */
    static final Duration STANDBY_HEALTHY_WITHIN = Duration.ofMinutes(3);

    /** How often the runtime is re-read while waiting for a standby. */
    static final Duration STANDBY_HEALTH_POLL = Duration.ofSeconds(5);

    /**
     * How long a standby gets to empty out before it is stopped with somebody still on it.
     *
     * <p>Generous on purpose, and it costs nothing: at this point the network is back and the only
     * thing still happening is {@code StandbyReturn} handing people home. Ninety seconds is longer
     * than its own one-minute self-rescue, so the ordinary end of a swap is a standby that is
     * already empty when this asks.</p>
     */
    static final Duration STANDBY_DRAINS_WITHIN = Duration.ofSeconds(90);

    private final ContainerOps containers;
    private final Occupancy occupancy;
    private final UpdateRun.Waiting clock;

    /** What this run has started and not yet stopped, so {@link #close} can be called twice. */
    private final Set<String> standing = new LinkedHashSet<>();

    Choreography(
            final @NotNull ContainerOps containers,
            final @NotNull Occupancy occupancy,
            final @NotNull UpdateRun.Waiting clock) {
        this.containers = containers;
        this.occupancy = occupancy;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- the trigger rule

    /**
     * Which standbys a run over these services needs.
     *
     * <p>Till's rule, 2026-09-19, in his words: <i>if the run covers the limbo it needs an extra
     * limbo; if it covers the proxy, an extra proxy; if it covers the SMP, a waiting room has to be
     * available the whole time.</i> The first two are this filter. <b>The third is already
     * satisfied by it</b> and that is worth saying rather than leaving as a coincidence: a run that
     * moves the SMP either leaves the limbo alone - in which case the waiting room is the limbo, up
     * the whole time, as it is on every ordinary backend update - or it moves the limbo too, in
     * which case the first rule has already put {@code limbo-standby} in this list. There is no
     * third case, so there is no third line.</p>
     *
     * <p>Static, taking names and returning names: the rule is the part worth asserting, and a rule
     * that needs a container runtime to be exercised is a rule nobody exercises.</p>
     *
     * @param moving the services this run is going to stop
     * @return the compose service names to start first, in {@code SERVICES_WITH_STANDBY} order
     */
    static @NotNull List<String> standbysFor(final @NotNull Collection<String> moving) {
        return Topology.SERVICES_WITH_STANDBY.stream()
                .filter(moving::contains)
                .map(Topology::standbyOf)
                .toList();
    }

    // ---------------------------------------------------------------- opening the window

    /**
     * Starts every standby this run needs and waits until each one is healthy.
     *
     * <p>A failure here stops whatever it had already started, so a refused run leaves the host
     * exactly as it found it - a standby left running would be a second network nobody asked for,
     * and the only thing making it visible is that steward-ui draws it (steward/125).</p>
     *
     * @return a window that {@link Window#opened() opened}, or one carrying the sentence to put in
     *         the report of a run that must now touch nothing
     */
    @NotNull
    Window open(final @NotNull Collection<String> moving) {
        final List<String> wanted = standbysFor(moving);
        if (wanted.isEmpty()) {
            return new Window(List.of(), null);
        }

        for (final String standby : wanted) {
            // recreate and NOT deploy: the standby has to come up on the image its live service is
            // running, and on this deployment that image is often one built on this host. See
            // ContainerOps#recreate.
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

    /** @return {@code null} when every one of them came up, or the sentence naming those that did not */
    private @Nullable String waitHealthy(final List<String> standbys) {
        final List<String> pending = new ArrayList<>(standbys);
        final Instant deadline = clock.now().plus(STANDBY_HEALTHY_WITHIN);
        while (true) {
            // The snapshot this iteration reads, once, and then asked about every pending service -
            // never a fresh call per service, which would let one report disagree with itself.
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
                    named.add(standby + " ("
                            + (seen.reached()
                                    ? seen.service(standby)
                                            .map(ServiceRuntime::describe)
                                            .orElse("no container for it in the project")
                                    : "the container runtime could not be read: " + seen.message())
                            + ")");
                }
                return String.join(", ", named) + " did not become healthy within " + STANDBY_HEALTHY_WITHIN.toMinutes()
                        + " minutes";
            }
            if (!clock.sleep(STANDBY_HEALTH_POLL)) {
                return "steward-worker stopped while waiting for " + String.join(", ", pending);
            }
        }
    }

    // ---------------------------------------------------------------- waiting for the players

    /**
     * Waits until nobody is on the services about to stop, and gives up after {@link #EMPTY_CAP}.
     *
     * @param moving the services this run is going to stop
     * @return {@code null} when the run may stop them without anything to say, or the sentence for
     *         the report - either "stopped with N player(s) still connected" or the honest one
     *         about not having been able to tell
     */
    @Nullable
    String waitUntilEmpty(final @NotNull Collection<String> moving) {
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
                return "steward-worker stopped while waiting for the players to be moved off "
                        + String.join(", ", watched);
            }
        }
    }

    /**
     * The sentence a run leaves behind when the cap ran out.
     *
     * <p>Static and taking the two maps, because this is the part of the wait worth asserting and
     * because both halves of it have been quietly dropped in this project before: a stop that
     * happened with somebody on it, and a stop that happened without anything being able to say.
     * The second is not the first - "nobody is on it" and "nothing has told me" are different
     * facts, and reporting the second as the first is how a wait stops being a wait.</p>
     */
    static @NotNull String stoppedAnyway(
            final @NotNull Map<String, Integer> occupied, final @NotNull List<String> unknown) {
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

    /** Whether a player could be standing on this service at all - the others need no wait. */
    private static boolean canCarryPlayers(final String service) {
        return Topology.SERVICES.stream().anyMatch(one -> one.name().equals(service));
    }

    // ---------------------------------------------------------------- closing the window

    /**
     * Stops every standby this run started, once it is empty.
     *
     * <p>Idempotent: a run that closes its window on the way out of a {@code finally} and has
     * already closed it on the ordinary path stops nothing twice. Called at every exit, including
     * the cancelled one - a standby left standing after a cancelled run is the only kind of leak
     * this mechanism has.</p>
     *
     * @return one sentence per standby, for the report; empty when this run opened no window
     */
    @NotNull
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

    /** @return {@code null} when it emptied out, or what was still on it when the patience ran out */
    private @Nullable String waitDrained(final String standby) {
        final Instant deadline = clock.now().plus(STANDBY_DRAINS_WITHIN);
        while (true) {
            final Instant now = clock.now();
            final OptionalInt players = Topology.standbyOf(Topology.PROXY).equals(standby)
                    ? occupancy.onStandbyProxy(now)
                    : occupancy.on(standby, now);
            if (players.isEmpty() || players.getAsInt() == 0) {
                // Empty is empty; "nothing said" is treated as empty HERE and nowhere else in this
                // class, and the asymmetry is deliberate. A standby nobody is hearing from is a
                // standby that is not holding a session open either - it has stopped writing
                // because it has stopped, or because its database connection is gone, and in both
                // cases leaving it running for ninety seconds serves nobody.
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
     * @param refusal  why the run must not go on, or {@code null} when it may
     */
    record Window(@NotNull List<String> standbys, @Nullable String refusal) {

        boolean opened() {
            return refusal == null;
        }

        boolean isEmpty() {
            return standbys.isEmpty();
        }
    }
}
