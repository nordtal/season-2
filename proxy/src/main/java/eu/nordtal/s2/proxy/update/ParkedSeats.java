package eu.nordtal.s2.proxy.update;

import eu.nordtal.s2.proxy.routing.PhaseServers;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where each player was standing before the proxy swapped out from under them, in memory, for the
 * one minute after a restart when anybody is coming back (season-2-ops/121).
 *
 * <p>Filled once from {@link SwapStore#takeAllSeats()} while the plugin starts - before Velocity
 * binds its listener, so no login can race the read - and emptied a player at a time as they
 * arrive. Nothing refills it: a seat is a fact about one run, and the run is over.</p>
 *
 * <h2>Whose seat actually changes anything, which is a shorter list than it looks</h2>
 * Routing on this network is a total function of the season phase: {@code PhaseRouting} sends every
 * non-admin to the one backend their phase names, and it does so on every login and on every phase
 * change. So for a player who is not an admin, <b>the seat and the phase's own answer agree by
 * construction</b> - the seat says {@code smp} because the phase said {@code smp} twenty seconds
 * ago and still does. Honouring it would change nothing, and honouring it in the one case where
 * they <em>disagree</em> - the phase moved during the swap - would put somebody on a backend the
 * current phase does not allow, past the check that exists to stop precisely that.
 *
 * <p>So a seat is honoured for an admin and for nobody else. An admin is the one player routing
 * deliberately does not move: they get around with {@code /server}, they are the reason
 * {@code RouteIntents} has an exemption at all, and "I was on hunger-games looking at something and
 * came back on the SMP" is the one way this feature is noticeably wrong for somebody.</p>
 *
 * <p>Every seat is still <em>written</em>, admin or not. It costs one statement for a table that is
 * emptied on the next start, and what it buys is a record of who was actually parked - which is the
 * thing somebody will want the morning after a swap that went badly.</p>
 */
public final class ParkedSeats {

    private final Map<UUID, String> seats = new ConcurrentHashMap<>();

    /**
     * @param rows every row {@link SwapStore#takeAllSeats()} returned, stale ones included
     * @param now  the proxy's clock; a row older than {@link SwapStore#SEAT_VALID_FOR} is dropped
     *             here rather than at the database, so that the statement stays one statement
     */
    public ParkedSeats(final List<SwapStore.Seat> rows, final Instant now) {
        Objects.requireNonNull(rows, "rows");
        Objects.requireNonNull(now, "now");
        final Map<UUID, String> fresh = new HashMap<>();
        for (final SwapStore.Seat seat : rows) {
            if (seat.isFresh(now)) {
                fresh.put(seat.playerUuid(), seat.server());
            }
        }
        seats.putAll(fresh);
    }

    /**
     * @param player who has just arrived
     * @return whether a seat is being held for them - they are coming back from the swap rather
     *         than walking into it. Asked by {@code RestartGate} and not consuming: the seat is
     *         spent when the waiting room lets them out, which is a second or two later
     */
    public boolean holds(final UUID player) {
        return seats.containsKey(player);
    }

    /** @return how many seats were carried over - for the startup log line, never a decision */
    public int size() {
        return seats.size();
    }

    /**
     * Where the waiting room should let this player out to.
     *
     * <p>Consuming: a seat is used once. A player who is released, fails to connect and is released
     * again goes by the phase the second time, which is the safe direction - the seat was already
     * tried and the phase's answer is the one that is true now.</p>
     *
     * @param player           who is being released
     * @param admin            whether they carry {@code discord_user.admin} - see the class comment
     *                         on why this is the whole of the permission
     * @param phaseDestination where {@code PhaseRouting#decideRelease} says they go
     * @param available        the backends registered on this proxy
     * @param servers          the backend names
     * @return where to actually connect them
     */
    public String releaseTo(final UUID player, final boolean admin, final String phaseDestination,
                            final Set<String> available, final PhaseServers servers) {
        final String seat = seats.remove(player);
        return destination(seat, admin, phaseDestination, available, servers);
    }

    /**
     * The rule on its own, so the four ways to get it wrong are all assertable: honouring a seat
     * for somebody routing owns, honouring one naming a backend this proxy does not have,
     * honouring one naming a waiting room - which would release a player into the room they are
     * standing in, a black screen with a stale title - and losing one that was fine.
     *
     * @param seat where they were, or {@code null} for every login that is not the far end of a
     *             swap
     * @return the seat when it may be honoured, otherwise {@code phaseDestination} unchanged
     */
    static String destination(final String seat, final boolean admin, final String phaseDestination,
                              final Set<String> available, final PhaseServers servers) {
        if (seat == null || !admin) {
            return phaseDestination;
        }
        if (!available.contains(seat) || servers.isWaitingRoom(seat)) {
            return phaseDestination;
        }
        return seat;
    }
}
