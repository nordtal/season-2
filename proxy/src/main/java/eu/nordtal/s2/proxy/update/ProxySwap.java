package eu.nordtal.s2.proxy.update;

import eu.nordtal.s2.common.update.UpdateDirectory;
import eu.nordtal.s2.proxy.online.OnlineCounts;
import eu.nordtal.s2.proxy.routing.ProxyRole;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;

import org.slf4j.Logger;

import java.net.InetSocketAddress;
import java.time.Clock;
import java.util.Objects;
import java.util.Set;

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

    /** Whether this run has already been acted on, so one run parks the network once. */
    private volatile boolean parked;

    /**
     * @param standby where the players go, from {@code SwapAddresses#standbyAddress}, or
     *                {@code null} when this deployment has no public address configured and
     *                therefore does not swap proxies
     */
    public ProxySwap(final ProxyServer proxy, final Logger logger, final UpdateDirectory updates,
                     final SwapStore seats, final ProxyRole role, final InetSocketAddress standby,
                     final Clock clock) {
        this.proxy = Objects.requireNonNull(proxy, "proxy");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.updates = Objects.requireNonNull(updates, "updates");
        this.seats = Objects.requireNonNull(seats, "seats");
        this.role = Objects.requireNonNull(role, "role");
        this.standby = standby;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * @return whether this proxy will park the network rather than drop it - for the startup log
     *         line, which is the only place anybody finds out before the day it matters
     */
    public boolean isArmed() {
        return standby != null && !role.isStandby();
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

        final Set<String> next;
        try {
            next = Evacuation.imminent(updates.running(), updates.countingDown(), clock.instant());
        } catch (final RuntimeException failure) {
            logger.warn("Could not read the update row; nobody was parked this pass", failure);
            return;
        }

        if (!next.contains(OWN_SERVICE)) {
            // Including every ordinary backend run. Reset here rather than on a timer so that a
            // second proxy run in the same session parks again.
            parked = false;
            return;
        }
        if (parked) {
            return;
        }
        parked = true;
        park();
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

        logger.info("The update moves this proxy: parking {} player(s) on {}:{} until it is back",
                players.size(), standby.getHostString(), standby.getPort());
        for (final Player player : players) {
            final String on = player.getCurrentServer()
                    .map(connection -> connection.getServerInfo().getName())
                    .orElse(null);
            if (on != null) {
                try {
                    seats.seat(player.getUniqueId(), on, clock.instant());
                } catch (final RuntimeException failure) {
                    logger.warn("Could not record where {} was standing; they will be routed by the"
                            + " phase when they come back", player.getUsername(), failure);
                }
            }
            try {
                player.transferToHost(standby);
            } catch (final RuntimeException failure) {
                // Velocity refuses the transfer outright for a client older than 1.20.5 - a
                // checkArgument, not a returned failure. One such player must not cost everybody
                // else theirs, which is the whole reason this is caught per player.
                logger.warn("Could not transfer {} to the standby proxy; they will be disconnected"
                        + " when this one stops", player.getUsername(), failure);
            }
        }
    }
}
