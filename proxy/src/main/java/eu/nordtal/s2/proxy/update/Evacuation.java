package eu.nordtal.s2.proxy.update;

import eu.nordtal.s2.common.update.UpdateDirectory;
import eu.nordtal.s2.proxy.routing.PhaseServers;
import eu.nordtal.s2.common.update.UpdateReport;
import eu.nordtal.s2.common.update.UpdateReports;
import eu.nordtal.s2.common.update.UpdateRequest;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import org.slf4j.Logger;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

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
 * <h2>Nobody is moved before the counter reaches zero - season-2-ops/118</h2>
 * This used to move people eight seconds early, and the reason was written down: a move asked for
 * in the last second would race the stop it was running away from. Till, 2026-09-20, saw the other
 * side of that trade - <i>thrown out four seconds before the end of the countdown</i> - and he is
 * right that any head start at all makes the counter a lie about when it happens.
 *
 * <p>The race it was protecting against is gone. Since season-2-ops/122 steward-worker waits after
 * the countdown until the services it is about to stop are empty, up to ten seconds, so a transfer
 * started at zero has time to finish. The head start was the answer to a question the worker now
 * answers properly, and two answers to one question is one answer too many.</p>
 *
 * <p>What replaces it is not a wider poll but a scheduled moment: {@code RestartWatch} already
 * schedules one task per beat against the row's instant, and the zero beat now runs this sweep as
 * well. The five-second poll stays what it always was - the guarantee behind the schedule, not the
 * thing that decides when.</p>
 *
 * <h2>What it refuses to do</h2>
 * <b>Evacuate into a waiting room that is itself being updated.</b> {@code limbo} is one of the four
 * Minecraft services and its jar moves like any other; a run that includes it has nowhere to put
 * anybody, and moving players onto a server that is thirty seconds from stopping would turn one
 * disconnect into two. Then nobody is moved and the run behaves exactly as it did before this class
 * existed - which is the honest fallback, not a silent one: it is logged as a warning each time.
 */
public final class Evacuation {

    private final ProxyServer proxy;
    private final Logger logger;
    private final UpdateDirectory updates;
    private final PhaseServers servers;
    /**
     * The backends a run currently has, or is about to have, stopped.
     *
     * <p>Volatile and replaced wholesale rather than mutated: {@link #isMoving} is read from the
     * sweep, from a plugin message and from a player's own arrival, none of which are this class's
     * thread, and a set being added to while it is iterated is the kind of failure that shows up
     * once a season.</p>
     */
    private volatile Set<String> moving = Set.of();

    /**
     * The backends somebody is deliberately holding down (season-2-ops/125).
     *
     * <p>Kept apart from {@link #moving} rather than folded into it, and the reason is the sentence
     * on the waiting room's screen. An update says "you will be moved back automatically" and is
     * nearly over; a hold ends when a person presses Start and not before. Told as the same thing
     * they would be the same promise, and the second one is a promise nothing here can keep.</p>
     */
    private volatile Set<String> held = Set.of();

    /** Whether the last pass had to refuse the evacuation, so the warning is logged once per run. */
    private volatile boolean warnedAboutWaitingRoom;

    public Evacuation(final ProxyServer proxy, final Logger logger,
                      final UpdateDirectory updates, final PhaseServers servers) {
        this.proxy = Objects.requireNonNull(proxy, "proxy");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.updates = Objects.requireNonNull(updates, "updates");
        this.servers = Objects.requireNonNull(servers, "servers");
    }

    /**
     * @param server a backend name
     * @return whether an update run has this backend stopped or is stopping it now. This is what
     *         puts {@code UPDATE} rather than {@code BACKEND} on the waiting room's screen, and it
     *         is a set lookup - it is asked once per held player per sweep
     */
    public boolean isMoving(final String server) {
        return server != null && moving.contains(server);
    }

    /**
     * @return whether a run has anything at all stopped or about to be
     *
     * <p>Half of what puts {@code OnlineWriter} on its fast cadence (season-2-ops/122); the other
     * half is {@code RestartWatch#isCountingDown}, and that one is the half that starts early
     * enough. steward-worker waits for those counts to reach zero before it stops a service and
     * gives up after ten seconds, and a number that is itself ten seconds old cannot answer that
     * question - so the processes that know a run is imminent are the ones that say when the
     * numbers have to be fresh. This one covers the whole outage after zero; the countdown covers
     * the thirty seconds before it.</p>
     */
    public boolean isAnyMoving() {
        return !moving.isEmpty();
    }

    /**
     * @param server a backend name
     * @return whether somebody is holding it down on purpose, which is what puts {@code HELD} on
     *         the waiting room's screen instead of {@code BACKEND} or {@code UPDATE}
     */
    public boolean isHeld(final String server) {
        return server != null && held.contains(server);
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
            // Read before anything can return early: a hold outlives its run, so the pass that has
            // no run to report is exactly the pass that still has to know about the hold.
            held = heldServices(updates.holds());
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

        // WHICH ROOM THIS RUN CAN USE (season-2-ops/120). The ordinary answer is the waiting room
        // itself; the run that stops the waiting room is the case this method used to have no
        // answer for, and `limbo-standby` is that answer.
        final String room = roomFor(next, servers.limbo(), servers.limboStandby(),
                name -> proxy.getServer(name).isPresent());
        if (room == null) {
            // Still the one case with no answer - but now it is narrower: the run has to include
            // the waiting room AND there has to be no standby registered on this proxy. Said out
            // loud every pass of the run rather than once ever: it is rare enough that a single
            // line in a week-old log is a line nobody finds.
            if (!warnedAboutWaitingRoom) {
                warnedAboutWaitingRoom = true;
                logger.warn("The update moves '{}' itself and no '{}' is registered on this proxy,"
                                + " so there is nowhere to put anybody: nobody is being evacuated"
                                + " and every connected player will be disconnected when the"
                                + " servers stop. The countdown is all the warning they get.",
                        servers.limbo(), servers.limboStandby());
            }
            moving = Set.of();
            return;
        }

        moving = next;
        evacuate(next, room);
    }

    /**
     * The waiting room this run can move people into, or {@code null} if it has none.
     *
     * <p>Three cases, and the middle one is the whole of season-2-ops/120:</p>
     * <ul>
     *   <li>the run leaves the waiting room alone - it is the destination, as it always was;</li>
     *   <li>the run stops the waiting room - {@code limbo-standby} is the destination, provided
     *       this proxy has one registered. It is a second limbo in the ordinary role, not a special
     *       case: a player sent there is a player waiting, and {@code PhaseServers#isWaitingRoom}
     *       is what makes every other part of this plugin agree;</li>
     *   <li>the run stops <em>both</em>, or there is no standby - nobody can be moved. A run that
     *       includes the standby is a run that has taken the ground out from under itself, and
     *       saying so is better than moving players onto a server that is about to stop.</li>
     * </ul>
     *
     * <p>Static and taking {@code registered} as a predicate for the same reason
     * {@link #imminent} is static: it is the decision, and a decision that needs a running
     * Velocity to be exercised is a decision nobody exercises.</p>
     *
     * @param next       the backends this run is about to stop
     * @param limbo      the waiting room's name
     * @param standby    the second waiting room's name
     * @param registered whether this proxy has a server under a given name
     * @return a backend name to move players into, or {@code null}
     */
    static String roomFor(final Set<String> next, final String limbo, final String standby,
                          final Predicate<String> registered) {
        if (!next.contains(limbo) && registered.test(limbo)) {
            return limbo;
        }
        if (!next.contains(standby) && registered.test(standby)) {
            return standby;
        }
        return null;
    }

    /**
     * The backends of the run that is happening or is about to.
     *
     * <p>Empty while a countdown is still running with time to spare: holding a player in the
     * waiting room for an outage that has not started - and that they can still see cancelled -
     * would put the title up before there was anything to say.</p>
     */
    private Set<String> imminent() {
        return imminent(updates.running());
    }

    /**
     * The services of those holds, as a set to look a backend up in (season-2-ops/125).
     *
     * <h2>Why a hold is read at all, when a run already says what is stopped</h2>
     * The DOWN run that writes a hold reaches {@code DONE} in seconds; the outage it started lasts
     * until a person presses Start. Reading only the run would put "Update in progress - you will
     * be moved back automatically" on the screen for half a minute and then fall back to "waiting
     * for the server", which is what it says about a server that crashed. Neither is true of a
     * server somebody stopped in order to work on it, and the difference is the one the person on
     * the black screen actually cares about.
     *
     * <p>Static and taking the list rather than the directory, for the reason {@link #imminent}
     * gives: this is the rule, and a rule a test cannot reach is a rule nothing holds.</p>
     */
    static Set<String> heldServices(final List<eu.nordtal.s2.common.update.ServiceHold> holds) {
        if (holds.isEmpty()) {
            return Set.of();
        }
        final Set<String> services = new HashSet<>();
        for (final eu.nordtal.s2.common.update.ServiceHold hold : holds) {
            services.add(hold.service());
        }
        return Set.copyOf(services);
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
     * @param running the run that is under way, from {@code UpdateDirectory#running()} - which is
     *                the row whose {@code not_before} has passed, so "under way" and "the counter
     *                has reached zero" are the same instant
     * @return the backends to clear, empty when there is nothing to clear yet
     */
    static Set<String> imminent(final Optional<UpdateRequest> running) {
        return running.map(Evacuation::backends).orElseGet(Set::of);
    }

    /**
     * The services a request's report says are moving.
     *
     * <p>Read off the report steward-worker has already written into the row, rather than worked out
     * again here. That is the same rule the rest of this network follows - the worker is the only
     * thing that decides what an update touches - and it is what keeps a proxy from evacuating a
     * server the run was never going to stop.</p>
     *
     * <p>A row with no readable report gives an empty set and therefore no evacuation. That is the
     * safe direction: the old behaviour, not a guess at every backend.</p>
     *
     * <p>Package-visible rather than private since season-2-ops/118: {@code RestartWatch} asks the
     * same question of the row it is <em>counting down</em>, so that the announcement can say which
     * service this is about. Two parsers of one report would be two answers to one question.</p>
     */
    static Set<String> backends(final UpdateRequest request) {
        return UpdateReports.parse(request.result())
                .map(report -> report.services().stream()
                        .filter(UpdateReport.ServiceLine::isMoving)
                        .map(UpdateReport.ServiceLine::service)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet()))
                .orElseGet(Set::of);
    }

    /** Moves everybody standing on one of those backends into the waiting room. */
    private void evacuate(final Set<String> backends, final String waitingRoom) {
        final RegisteredServer limbo = proxy.getServer(waitingRoom).orElse(null);
        if (limbo == null) {
            // roomFor already asked this proxy for the server, so reaching here means it was
            // unregistered between the two calls. Kept rather than dropped: it is the same
            // sentence, and a null here would be a NullPointerException in a scheduler task.
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
