package eu.nordtal.s2.proxy.update;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import eu.nordtal.s2.common.update.UpdateDirectory;
import eu.nordtal.s2.proxy.ProxyRole;
import eu.nordtal.s2.proxy.online.OnlineCounts;
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
 * The live proxy's half of a proxy swap: it parks the whole network on the standby before it stops itself.
 *
 * This is not {@code Evacuation}: that class moves players between backends, and it works because
 * the thing doing the moving stays up. A run that stops the proxy has no such luxury: the process
 * that would move anybody is the process being stopped, and the connection it would move them over
 * is the one it holds itself. Velocity has exactly one tool for that - the transfer packet, which
 * hands the client an address and asks it to reconnect there. Everything below follows from that
 * one fact.
 *
 * What a player sees is the loading screen, twice: once on the way to the standby and once on the
 * way back. In between they sit in {@code limbo-standby} under the same "Update in progress - you
 * will be moved back automatically" title the backend updates already use. They go over the
 * waiting room and not straight back onto the SMP, which is the whole reason this is safe to build
 * without a measurement: a player who never rejoins the backend they just left cannot collide with
 * their own session there.
 *
 * What it costs is ten seconds of dead time on the public port while the proxy itself restarts:
 * somebody trying to join in exactly that window sees an outage. That is the paid price of not
 * putting a doorman in front of 25565, not a defect. What it does not cost is the session: nobody
 * is disconnected, nobody loses their place in the world, and the Paper servers are not touched at
 * all by a run that only moves the proxy.
 *
 * Two ways this does nothing, both on purpose: no {@code network.yml#public-address} means there
 * is no address to send anybody to, so an update takes the network down the way it always did -
 * that is a deployment that never asked for this feature, so it is a startup log line and not a
 * refusal to run. And when this process is the standby, it is the destination, and a standby that
 * parked its players on itself would be a loop with everybody inside it - {@code StandbyReturn} is
 * the standby's half.
 */
public final class ProxySwap {

    /**
     * The compose service name of the proxy, as {@code steward-worker}'s report spells it.
     *
     * {@code OnlineCounts.PROXY} rather than a second literal: this module already had to know
     * the name to write its own row of {@code online_count}, and two copies of a service name is
     * one copy that is silently wrong. The worker's own is {@code Topology.PROXY}, in a module this
     * one cannot see; if that ever changes, the swap stops happening and the log says nothing,
     * which is why {@code ProxySwapDecisionTest} states the name out loud.
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
     * When this process started.
     *
     * How it tells a run that is about to stop it from one that has already been through it.
     *
     * A run's row stays RUNNING while the worker works, and it goes on naming {@code proxy} as a
     * moving service long after the proxy has been stopped, started and become this process. A new
     * proxy that read that row without this check could decide it was about to stop, shut its door
     * and refuse a player the standby was at that moment handing back. A process that started after
     * the run's own zero cannot be the process the run is waiting to stop.
     */
    private final Instant startedAt;

    /**
     * Whether this run has already been acted on, so one run parks the network once.
     *
     * Read from outside, also whether this proxy is inside a run that stops it.
     *
     * Both readings at once, and that is the point: parking is a moment - it happens when the
     * countdown reaches zero, to whoever is connected then. Being about to stop is a state, and it
     * lasts the seconds the worker spends waiting for the backends to empty. A player who connected
     * inside that window was never parked and met "Proxy shutting down" instead.
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
     *         should be let past the door. False on the standby and false on a
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
     * Read by {@code RestartWatch} once per countdown, to tell a player
     * whether they are about to see a loading screen or a disconnect. It is the same question
     * {@link #decide} asks last and for the same reason - it opens a socket, so it is asked once a
     * run and never on a pass that has nothing to do.
     *
     * False on the standby itself and on a proxy with no {@code public-address}: neither parks
     * anybody, so neither has a standby in the sense this question means.
     */
    public boolean canPark() {
        return isArmed() && probe.answers(standby, STANDBY_ANSWERS_WITHIN);
    }

    /**
     * One pass.
     *
     * Scheduled beside {@code Evacuation}, on the same interval and for the same reason it has a task of its own: a
     * watch that throws is a watch Velocity stops running, and the failure mode of that is a season of updates during
     * which the proxy takes the network down with it and nothing in the log says why.
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
        // The door first and separately from the action: park() happens once, being shut lasts as long as the run.
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
     * Not just "PARK happened": a pass that parks is followed by pass after pass of
     * {@link Pass#ALREADY_DONE} until the process actually goes. The door has to stay shut across
     * all of them, and it has to open again on {@link Pass#IDLE}, because a run can be the last
     * one and the proxy can still be here afterwards (a run that stops nothing, or a second proxy
     * run in the same session). {@link Pass#STANDBY_MISSING} shuts it as firmly as
     * {@link Pass#PARK} does: there the arrival would be dropped rather than moved, which is the
     * worse of the two, not the better.
     */
    static boolean doorAfter(final Pass pass, final boolean wasShut) {
        return switch (pass) {
            // ALREADY_MOVED is the run finished with this proxy while still running; the door has to stay open.
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
     *         started by the run. A process that was here before zero is the one the run
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
         * Told apart from {@link #IDLE} rather than folded into it because they are opposite
         * situations that happen to want the same inaction - and because the one that would be
         * wrong in silence is this one.
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
     * Being configured is not being there: {@link #isArmed()} only says an address was worked out
     * at startup. Whether anything listens on it is a fact about right now, and for most of the
     * season it is false: {@code proxy-standby} lives in a compose profile of its own and is
     * stopped until somebody starts it. Parking onto a dead address does not fail safe - every
     * player is transferred somewhere nothing answers and is dropped, which is strictly worse than
     * the plain restart the swap exists to avoid. So the standby is asked, and a silent one means
     * this update behaves exactly as it did before any of this was built.
     *
     * The choreography that starts the standby before the run reaches the proxy does not exist
     * yet, so {@link Pass#STANDBY_MISSING} is at present the normal outcome rather than an
     * exceptional one.
     *
     * @param standbyAnswers asked last and never otherwise: it opens a socket, and a pass
     *                       that has nothing to do must cost nothing
     */
    static Pass decide(
            final Set<String> imminent,
            final boolean alreadyParked,
            final boolean alreadyMoved,
            final BooleanSupplier standbyAnswers) {
        if (!imminent.contains(OWN_SERVICE)) {
            // Reported as IDLE rather than handled on a timer so a second proxy run in the same session parks again.
            return Pass.IDLE;
        }
        if (alreadyMoved) {
            // This process is what the run already started; parking now would hand the network to the standby twice.
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
     * The seat is written before the transfer, one player at a time, and a failure to
     * write one does not stop the transfer: a player who arrives on the other side without a seat
     * is routed by the phase like any other login, which is the old behaviour and not a loss. A
     * player left on a proxy that is about to stop is a disconnect. Those are the two outcomes, and
     * they are not close.
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
     * Public because the park is not only a moment: whoever arrives between the zero and the
     * actual stop goes the same way as everybody who was already here, and {@code RestartGate} is
     * what calls this for them. One player at a time is how it was always written - a failure to
     * seat does not stop the transfer, and a client that cannot be transferred must not cost
     * everybody else theirs.
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
            // Velocity refuses the transfer outright for a client older than 1.20.5; caught per player for that reason.
            logger.warn("Could not transfer {} to the standby proxy", player.getUsername(), failure);
            return false;
        }
    }
}
