package eu.nordtal.s2.networkcontrol.update;

import eu.nordtal.s2.common.update.UpdateDirectory;
import eu.nordtal.s2.common.update.UpdateReport;
import eu.nordtal.s2.common.update.UpdateReports;
import eu.nordtal.s2.common.update.UpdateRequest;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import org.slf4j.Logger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Moves the players off a backend an update is about to stop, and keeps the waiting room able to
 * say why they are sitting in it.
 *
 * <h2>What happened without this</h2>
 * The countdown warned everybody and then the container stopped underneath them. Where they went
 * next was not decided anywhere in this repository: {@code RestartWatch} says in as many words that
 * it does not move anybody, and {@code BackendKick} changes the text of a kick and never its
 * destination. What was left was Velocity's own fallback through {@code velocity.toml}'s
 * {@code try} list - a behaviour nobody here has observed, on a proxy major nobody here has run for
 * long. Both of its outcomes are bad ones: a disconnect screen after a countdown that said "back in
 * a minute", or a silent landing in the waiting room with a title that says a server is missing.
 *
 * <h2>The return needs nothing new, and that is deliberate</h2>
 * A player moved here arrives on {@code limbo} like any other arrival, so {@code PackStation} puts
 * them in the {@code WaitingBook}, the sweep re-asks every five seconds, and the moment the backend
 * is registered and takes a connection again they are released onto it. That path already existed
 * for a backend that is merely down. All this adds is the two things it could not know: <b>that</b>
 * they should be here at all, and <b>why</b> - which is the difference between {@code BACKEND} and
 * {@code UPDATE} on the screen.
 *
 * <h2>Two rows, because an outage is two phases</h2>
 * The countdown row says an outage is coming and carries the plan; the running row says it is
 * happening. Reading only the first would put the title up for thirty seconds and take it down for
 * the five minutes that matter, and reading only the second would move players after the servers
 * had already gone.
 *
 * <h2>What it refuses to do</h2>
 * <b>Evacuate into a waiting room that is itself being updated.</b> {@code limbo} is one of the four
 * Minecraft services and its jar moves like any other; a run that includes it has nowhere to put
 * anybody, and moving players onto a server that is thirty seconds from stopping would turn one
 * disconnect into two. Then nobody is moved and the run behaves exactly as it did before this class
 * existed - which is the honest fallback, not a silent one: it is logged as a warning each time.
 */
public final class Evacuation {

    /**
     * How long before the servers stop the players are moved.
     *
     * <p>Wider than the poll interval, which is what makes at least one pass land inside the window:
     * ticks are five seconds apart and this is eight, so a countdown cannot slip past unevacuated
     * between two of them. It is also long enough for the connection to actually complete - a move
     * asked for in the last second would race the stop it is running away from.</p>
     */
    static final Duration EVACUATE_BEFORE = Duration.ofSeconds(8);

    private final ProxyServer proxy;
    private final Logger logger;
    private final UpdateDirectory updates;
    private final String waitingRoom;
    private final Clock clock;

    /**
     * The backends a run currently has, or is about to have, stopped.
     *
     * <p>Volatile and replaced wholesale rather than mutated: {@link #isMoving} is read from the
     * sweep, from a plugin message and from a player's own arrival, none of which are this class's
     * thread, and a set being added to while it is iterated is the kind of failure that shows up
     * once a season.</p>
     */
    private volatile Set<String> moving = Set.of();

    /** Whether the last pass had to refuse the evacuation, so the warning is logged once per run. */
    private volatile boolean warnedAboutWaitingRoom;

    public Evacuation(final ProxyServer proxy, final Logger logger,
                      final UpdateDirectory updates, final String waitingRoom, final Clock clock) {
        this.proxy = Objects.requireNonNull(proxy, "proxy");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.updates = Objects.requireNonNull(updates, "updates");
        this.waitingRoom = Objects.requireNonNull(waitingRoom, "waitingRoom");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * @param server a backend name
     * @return whether an update run has it stopped, or is within {@link #EVACUATE_BEFORE} of doing
     *         so. This is what puts {@code UPDATE} rather than {@code BACKEND} on the waiting room's
     *         screen, and it is a set lookup - it is asked once per held player per sweep
     */
    public boolean isMoving(final String server) {
        return server != null && moving.contains(server);
    }

    /**
     * One pass. Scheduled beside {@code RestartWatch}, on the same interval and the same
     * {@code nordtal_update} notification.
     *
     * <p>Never throws, for the reason {@code RestartWatch} gives: a task that throws is a task
     * Velocity stops running, and the failure mode of that is a season of updates during which
     * nobody is moved out of the way and nothing in the log says why.</p>
     */
    public void check() {
        final Set<String> next;
        try {
            next = imminent();
        } catch (final RuntimeException failure) {
            // A database that cannot be read is not a reason to move anybody, and it is not a
            // reason to stop holding the players already being held either: the set from the last
            // pass stands until a pass can read one.
            logger.warn("Could not read the update row; nobody was moved this pass", failure);
            return;
        }

        if (next.isEmpty()) {
            moving = Set.of();
            warnedAboutWaitingRoom = false;
            return;
        }

        if (next.contains(waitingRoom)) {
            // The one case with no answer. Said out loud every pass of the run rather than once
            // ever: a run that includes the waiting room is rare enough that a single line in a
            // week-old log is a line nobody finds.
            if (!warnedAboutWaitingRoom) {
                warnedAboutWaitingRoom = true;
                logger.warn("The update moves '{}' itself, so there is nowhere to put anybody:"
                                + " nobody is being evacuated and every connected player will be"
                                + " disconnected when the servers stop. The countdown is all the"
                                + " warning they get.", waitingRoom);
            }
            moving = Set.of();
            return;
        }

        moving = next;
        evacuate(next);
    }

    /**
     * The backends of the run that is happening or is about to.
     *
     * <p>Empty while a countdown is still running with time to spare: holding a player in the
     * waiting room for an outage that has not started - and that they can still see cancelled -
     * would put the title up before there was anything to say.</p>
     */
    private Set<String> imminent() {
        return imminent(updates.running(), updates.countingDown(), clock.instant());
    }

    /**
     * The same decision, as a function of the three things it depends on.
     *
     * <p>Static and taking the rows rather than the directory, so it can be exercised without a
     * database and - the point - without a proxy. It is the same split {@code LimboHold} has from
     * {@code PackStation}: the rule is here and every Velocity call is in {@link #evacuate}, which
     * is what makes the rule assertable at all. The three ways to get it wrong are all in these six
     * lines: moving people for a countdown that is still cancellable, not moving them once the run
     * has started, and evacuating a server the run was never going to stop.</p>
     *
     * @param running      the run that is under way, from {@code UpdateDirectory#running()}
     * @param countingDown the run that is about to be, from {@code UpdateDirectory#countingDown()}
     * @param now          the proxy's clock
     * @return the backends to clear, empty when there is nothing to clear yet
     */
    static Set<String> imminent(final Optional<UpdateRequest> running,
                                final Optional<UpdateRequest> countingDown, final Instant now) {
        if (running.isPresent()) {
            return backends(running.get());
        }
        return countingDown
                .filter(request -> request.untilDue(now).compareTo(EVACUATE_BEFORE) <= 0)
                .map(Evacuation::backends)
                .orElseGet(Set::of);
    }

    /**
     * The services a request's report says are moving.
     *
     * <p>Read off the report the updater has already written into the row, rather than worked out
     * again here. That is the same rule the rest of this network follows - the updater is the only
     * thing that decides what an update touches - and it is what keeps a proxy from evacuating a
     * server the run was never going to stop.</p>
     *
     * <p>A row with no readable report gives an empty set and therefore no evacuation. That is the
     * safe direction: the old behaviour, not a guess at every backend.</p>
     */
    private static Set<String> backends(final UpdateRequest request) {
        return UpdateReports.parse(request.result())
                .map(report -> report.services().stream()
                        .filter(UpdateReport.ServiceLine::isMoving)
                        .map(UpdateReport.ServiceLine::service)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet()))
                .orElseGet(Set::of);
    }

    /** Moves everybody standing on one of those backends into the waiting room. */
    private void evacuate(final Set<String> backends) {
        final RegisteredServer limbo = proxy.getServer(waitingRoom).orElse(null);
        if (limbo == null) {
            logger.error("The update moves {} and there is no '{}' registered on this proxy, so"
                            + " nobody can be moved out of the way", backends, waitingRoom);
            return;
        }

        final List<Player> leaving = new java.util.ArrayList<>();
        for (final Player player : proxy.getAllPlayers()) {
            final String on = player.getCurrentServer()
                    .map(connection -> connection.getServerInfo().getName())
                    .orElse(null);
            if (on != null && backends.contains(on)) {
                leaving.add(player);
            }
        }
        if (leaving.isEmpty()) {
            return;
        }

        logger.info("Moving {} player(s) off {} into '{}' before the update stops them",
                leaving.size(), new HashSet<>(backends), waitingRoom);
        for (final Player player : leaving) {
            // fireAndForget rather than waiting on the future: this runs on the proxy's scheduler
            // with seconds to spare, and blocking it on one slow connection would delay everybody
            // else's move past the stop it exists to beat. A move that fails leaves the player
            // exactly where they were, which is what would have happened anyway.
            try {
                player.createConnectionRequest(limbo).fireAndForget();
            } catch (final RuntimeException failure) {
                logger.warn("Could not move {} into '{}' before the update",
                        player.getUsername(), waitingRoom, failure);
            }
        }
    }
}
