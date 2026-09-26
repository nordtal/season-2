package eu.nordtal.s2.proxy.update;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import eu.nordtal.s2.common.update.UpdateDirectory;
import eu.nordtal.s2.proxy.online.OnlineCounts;
import eu.nordtal.s2.proxy.routing.ProxyRole;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;

/**
 * The live proxy's half of a proxy swap: it parks the whole network on the standby before it stops
 * itself (season-2-ops/121).
 *
 * <h2>Why this is not {@code Evacuation}</h2>
 * {@code Evacuation} moves players between <em>backends</em>, and it works because the thing doing
 * the moving stays up. A run that stops the proxy has no such luxury: the process that would move
 * anybody is the process being stopped, and the connection it would move them over is the one it
 * holds itself. Velocity has exactly one tool for that - the transfer packet, which hands the
 * <b>client</b> an address and asks it to reconnect there. Everything below follows from that one
 * fact.
 *
 * <h2>What a player sees</h2>
 * The loading screen, twice: once on the way to the standby and once on the way back. In between
 * they sit in {@code limbo-standby} under the same "Update in progress - you will be moved back
 * automatically" title the backend updates already use. <b>They go over the waiting room and not
 * straight back onto the SMP</b>, which is Till's decision of 2026-09-19 and is the whole reason
 * this is safe to build without a measurement: a player who never rejoins the backend they just
 * left cannot collide with their own session there, so the open question in the ticket - does Paper
 * refuse the second login of a UUID whose first is still being cleaned up - cannot reach this code.
 *
 * <h2>What it costs, and what it does not</h2>
 * Ten seconds of dead time on the public port while the proxy itself restarts: somebody trying to
 * join in exactly that window sees an outage. That is the paid price of not putting a doorman in
 * front of 25565 (Till, 2026-09-19), not a defect.
 *
 * <p>What it does not cost is the session. Nobody is disconnected, nobody loses their place in the
 * world, and the Paper servers are not touched at all by a run that only moves the proxy.</p>
 *
 * <h2>The two ways this does nothing, both on purpose</h2>
 * <ul>
 *   <li><b>No {@code network.yml#public-address}</b> - then there is no address to send anybody to.
 *       An update takes the network down the way it always did. That is a deployment that never
 *       asked for this feature, so it is a startup log line and not a refusal to run.</li>
 *   <li><b>This process is the standby</b> - it is the destination, and a standby that parked its
 *       players on itself would be a loop with everybody inside it. {@code StandbyReturn} is the
 *       standby's half.</li>
 * </ul>
 */
public final class ProxySwap {

    /**
     * The compose service name of the proxy, as {@code steward-worker}'s report spells it.
     *
     * <p>{@code OnlineCounts.PROXY} rather than a second literal: this module already had to know
     * the name to write its own row of {@code online_count}, and two copies of a service name is
     * one copy that is silently wrong. The worker's own is {@code Topology.PROXY}, in a module this
     * one cannot see; if that ever changes, the swap stops happening and the log says nothing,
     * which is why {@code ProxySwapDecisionTest} states the name out loud.</p>
     */
    static final String OWN_SERVICE = OnlineCounts.PROXY;

    private final ProxyServer proxy;
    private final Logger logger;
    private final UpdateDirectory updates;
    private final SwapStore seats;
    private final ProxyRole role;
    private final InetSocketAddress standby;
    private final Clock clock;
    private final StandbyReturn.Probe probe;

    /**
     * When this process started, which is how it tells a run that is about to stop it from one that
     * has already <b>been</b> through it (season-2-ops/151).
     *
     * <p>A run's row stays RUNNING while the worker works, and it goes on naming {@code proxy} as a
     * moving service long after the proxy has been stopped, started and become this process. Run 76
     * on this host: the new proxy read that row, decided it was about to stop, shut its door and
     * refused the player the standby was at that moment handing back. A process that started after
     * the run's own zero cannot be the process the run is waiting to stop.</p>
     */
    private final Instant startedAt;

    /**
     * Whether this run has already been acted on, so one run parks the network once - and, read
     * from outside, whether this proxy is inside a run that stops it.
     *
     * <h2>Both readings at once, and that is the point (season-2-ops/151)</h2>
     * Parking is a <b>moment</b>: it happens when the countdown reaches zero, to whoever is
     * connected then. Being about to stop is a <b>state</b>, and it lasts the seconds the worker
     * spends waiting for the backends to empty - sixteen of them in run 59 on 2026-09-20. A player
     * who connected inside that window was never parked and met "Proxy shutting down" instead.
     * {@link #isStopping()} is that state, and {@code RestartGate} is what it is for.
     */
    private volatile boolean parked;

    /**
     * @param standby where the players go, from {@code SwapAddresses#standbyAddress}, or
     *                {@code null} when this deployment has no public address configured and
     *                therefore does not swap proxies
     */
    public ProxySwap(
            final ProxyServer proxy,
            final Logger logger,
            final UpdateDirectory updates,
            final SwapStore seats,
            final ProxyRole role,
            final InetSocketAddress standby,
            final Clock clock) {
        this(proxy, logger, updates, seats, role, standby, clock, StandbyReturn::connects);
    }

    /**
     * @param probe how to ask whether the standby is actually up. The same seam
     *              {@link StandbyReturn} uses, and for the same reason: the decision is what is
     *              worth asserting, and asserting it against a real socket would mean binding a
     *              port in a unit test
     */
    ProxySwap(
            final ProxyServer proxy,
            final Logger logger,
            final UpdateDirectory updates,
            final SwapStore seats,
            final ProxyRole role,
            final InetSocketAddress standby,
            final Clock clock,
            final StandbyReturn.Probe probe) {
        this.proxy = Objects.requireNonNull(proxy, "proxy");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.updates = Objects.requireNonNull(updates, "updates");
        this.seats = Objects.requireNonNull(seats, "seats");
        this.role = Objects.requireNonNull(role, "role");
        this.standby = standby;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.probe = Objects.requireNonNull(probe, "probe");
        this.startedAt = this.clock.instant();
    }

    /** How long the standby gets to answer before this pass decides it is not there. */
    static final Duration STANDBY_ANSWERS_WITHIN = Duration.ofSeconds(1);

    /**
     * @return whether this proxy will park the network rather than drop it - for the startup log
     *         line, which is the only place anybody finds out before the day it matters
     */
    public boolean isArmed() {
        return standby != null && !role.isStandby();
    }

    /**
     * @return whether a run that stops this proxy has already reached zero, so that nobody new
     *         should be let past the door (season-2-ops/151). False on the standby and false on a
     *         proxy with no {@code public-address}: both leave {@link #check()} before it decides
     *         anything, and a deployment that does not swap proxies takes the network down the way
     *         it always did - the door would only change the screen a few of them see
     */
    public boolean isStopping() {
        return parked;
    }

    /**
     * Whether there is a standby proxy answering right now, for somebody who needs to say so.
     *
     * <p>Read by {@code RestartWatch} once per countdown (season-2-ops/118), to tell a player
     * whether they are about to see a loading screen or a disconnect. It is the same question
     * {@link #decide} asks last and for the same reason - it opens a socket, so it is asked once a
     * run and never on a pass that has nothing to do.</p>
     *
     * <p>False on the standby itself and on a proxy with no {@code public-address}: neither parks
     * anybody, so neither has a standby in the sense this question means.</p>
     */
    public boolean canPark() {
        return isArmed() && probe.answers(standby, STANDBY_ANSWERS_WITHIN);
    }

    /**
     * One pass. Scheduled beside {@code Evacuation}, on the same interval and for the same reason it
     * has a task of its own: a watch that throws is a watch Velocity stops running, and the failure
     * mode of that is a season of updates during which the proxy takes the network down with it and
     * nothing in the log says why.
     */
    public void check() {
        if (!isArmed()) {
            return;
        }

        final Optional<eu.nordtal.s2.common.update.UpdateRequest> running;
        try {
            running = updates.running();
        } catch (final RuntimeException failure) {
            logger.warn("Could not read the update row; nobody was parked this pass", failure);
            return;
        }
        final Set<String> next = Evacuation.imminent(running);
        final boolean alreadyMoved = running.map(request -> hasBeenThroughMe(startedAt, request.notBefore()))
                .orElse(false);

        final Pass pass = decide(next, parked, alreadyMoved, () -> probe.answers(standby, STANDBY_ANSWERS_WITHIN));
        // THE DOOR FIRST, AND SEPARATELY FROM THE ACTION. `park()` happens once; being shut lasts
        // as long as the run does (season-2-ops/151).
        parked = doorAfter(pass, parked);
        switch (pass) {
            case IDLE, ALREADY_DONE, ALREADY_MOVED -> {}
            case STANDBY_MISSING ->
                logger.warn(
                        "The proxy is about to stop and {}:{} does not answer, so nobody can "
                                + "be parked - this update takes the network down the way it "
                                + "always did. The standby has to be running BEFORE the run "
                                + "reaches this proxy.",
                        standby.getHostString(),
                        standby.getPort());
            case PARK -> park();
        }
    }

    /**
     * Whether the door is shut once a pass has returned {@code pass}.
     *
     * <h2>Why this is not just "PARK happened"</h2>
     * A pass that parks is followed by pass after pass of {@link Pass#ALREADY_DONE} until the
     * process actually goes - sixteen seconds of them in run 59. The door has to stay shut across
     * all of them, and it has to open again on {@link Pass#IDLE}, because a run can be the last
     * one and the proxy can still be here afterwards (a run that stops nothing, or a second proxy
     * run in the same session). {@link Pass#STANDBY_MISSING} shuts it as firmly as
     * {@link Pass#PARK} does: there the arrival would be dropped rather than moved, which is the
     * worse of the two, not the better.
     */
    static boolean doorAfter(final Pass pass, final boolean wasShut) {
        return switch (pass) {
            // ALREADY_MOVED is the run that has finished with this proxy while still running. The
            // door has to be OPEN there, and firmly: the players the standby is handing back are
            // arriving in exactly those seconds (season-2-ops/151, run 76).
            case IDLE, ALREADY_MOVED -> false;
            case ALREADY_DONE -> wasShut;
            case STANDBY_MISSING, PARK -> true;
        };
    }

    /**
     * Whether the run has already stopped and started this process.
     *
     * @param startedAt  when this process started
     * @param notBefore  the run's own zero, from its row
     * @return whether this process began after that instant, which it can only have done by being
     *         started <em>by</em> the run. A process that was here before zero is the one the run
     *         is still waiting to stop
     */
    static boolean hasBeenThroughMe(final Instant startedAt, final Instant notBefore) {
        return notBefore != null && startedAt.isAfter(notBefore);
    }

    /** What one pass of {@link #check()} does. */
    enum Pass {

        /** No run is about to stop this proxy. */
        IDLE,

        /** One is, and this proxy has already acted on it. */
        ALREADY_DONE,

        /**
         * One is, and it has already been through this proxy: this process was started by it.
         *
         * <p>Told apart from {@link #IDLE} rather than folded into it because they are opposite
         * situations that happen to want the same inaction - and because the one that would be
         * wrong in silence is this one.</p>
         */
        ALREADY_MOVED,

        /** One is, and there is no standby answering to park the network on. */
        STANDBY_MISSING,

        /** One is, the standby is up, and everybody goes there now. */
        PARK
    }

    /**
     * The whole decision, without a proxy, a socket or a clock.
     *
     * <h2>Being configured is not being there (season-2-ops/139)</h2>
     * {@link #isArmed()} only says an address was worked out at startup. Whether anything listens
     * on it is a fact about <em>right now</em>, and for most of the season it is false:
     * {@code proxy-standby} lives in a compose profile of its own and is stopped until somebody
     * starts it. Parking onto a dead address does not fail safe - every player is transferred
     * somewhere nothing answers and is dropped, which is strictly worse than the plain restart the
     * swap exists to avoid. So the standby is asked, and a silent one means this update behaves
     * exactly as it did before any of this was built.
     *
     * <p>The choreography that starts the standby before the run reaches the proxy is
     * season-2-ops/122 and does not exist yet, so {@link Pass#STANDBY_MISSING} is at present the
     * normal outcome rather than an exceptional one.</p>
     *
     * @param standbyAnswers asked <b>last</b> and never otherwise: it opens a socket, and a pass
     *                       that has nothing to do must cost nothing
     */
    static Pass decide(
            final Set<String> imminent,
            final boolean alreadyParked,
            final boolean alreadyMoved,
            final BooleanSupplier standbyAnswers) {
        if (!imminent.contains(OWN_SERVICE)) {
            // Including every ordinary backend run. Reported as IDLE rather than handled on a timer
            // so that a second proxy run in the same session parks again.
            return Pass.IDLE;
        }
        if (alreadyMoved) {
            // The run stopped this proxy already and this process is what it started. Parking now
            // would hand the network to the standby a second time, for a stop that is never coming.
            return Pass.ALREADY_MOVED;
        }
        if (alreadyParked) {
            return Pass.ALREADY_DONE;
        }
        return standbyAnswers.getAsBoolean() ? Pass.PARK : Pass.STANDBY_MISSING;
    }

    /**
     * Seats everybody and hands them the standby's address.
     *
     * <p>The seat is written <b>before</b> the transfer, one player at a time, and a failure to
     * write one does not stop the transfer: a player who arrives on the other side without a seat
     * is routed by the phase like any other login, which is the old behaviour and not a loss. A
     * player left on a proxy that is about to stop is a disconnect. Those are the two outcomes, and
     * they are not close.</p>
     */
    private void park() {
        final var players = proxy.getAllPlayers();
        if (players.isEmpty()) {
            logger.info("The update moves this proxy and nobody is connected: nothing to park");
            return;
        }

        logger.info(
                "The update moves this proxy: parking {} player(s) on {}:{} until it is back",
                players.size(),
                standby.getHostString(),
                standby.getPort());
        for (final Player player : players) {
            park(player);
        }
    }

    /**
     * Seats one player and hands them the standby's address.
     *
     * <p>Public since season-2-ops/151, because the park stopped being only a moment: whoever
     * arrives between the zero and the actual stop goes the same way as everybody who was already
     * here, and {@code RestartGate} is what calls this for them. One player at a time is how it was
     * always written - a failure to seat does not stop the transfer, and a client that cannot be
     * transferred must not cost everybody else theirs.</p>
     *
     * @param player who to hand over
     * @return whether the transfer was sent. {@code false} is the one case that still ends in a
     *         screen, and the caller is the one that decides which
     */
    public boolean park(final Player player) {
        final String on = player.getCurrentServer()
                .map(connection -> connection.getServerInfo().getName())
                .orElse(null);
        if (on != null) {
            try {
                seats.seat(player.getUniqueId(), on, clock.instant());
            } catch (final RuntimeException failure) {
                logger.warn(
                        "Could not record where {} was standing; they will be routed by the"
                                + " phase when they come back",
                        player.getUsername(),
                        failure);
            }
        }
        try {
            player.transferToHost(standby);
            return true;
        } catch (final RuntimeException failure) {
            // Velocity refuses the transfer outright for a client older than 1.20.5 - a
            // checkArgument, not a returned failure. One such player must not cost everybody
            // else theirs, which is the whole reason this is caught per player.
            logger.warn("Could not transfer {} to the standby proxy", player.getUsername(), failure);
            return false;
        }
    }
}
