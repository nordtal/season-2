package eu.nordtal.season.proxy.update;

import eu.nordtal.season.proxy.PhaseServers;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * Where each player stood before a proxy swap, held in memory for the minute after a restart.
 *
 * Filled once before Velocity binds and emptied as players arrive; only an admin's seat is honoured.
 */
public final class ParkedSeats {

    private final Map<UUID, String> seats = new ConcurrentHashMap<>();

    /**
     * Keeps the seats that are still fresh.
     *
     * @param rows every row {@link SwapStore#takeAllSeats()} returned, stale ones included
     * @param now the proxy's clock; rows older than {@link SwapStore#SEAT_VALID_FOR} are dropped here
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

    /** Returns whether a seat is held for this player, without consuming it. */
    public boolean holds(final UUID player) {
        return seats.containsKey(player);
    }

    /** Returns how many seats were carried over, for the startup log line only. */
    public int size() {
        return seats.size();
    }

    /**
     * Where the waiting room should let this player out to, consuming the seat.
     *
     * @param player who is being released
     * @param admin whether they carry {@code discord_user.admin}, the only thing that lets a seat count
     * @param phaseDestination where {@code PhaseRouting#decideRelease} says they go
     * @param available the backends registered on this proxy
     * @param servers the backend names
     * @return where to actually connect them
     */
    public String releaseTo(
            final UUID player,
            final boolean admin,
            final String phaseDestination,
            final Set<String> available,
            final PhaseServers servers) {
        final String seat = seats.remove(player);
        return destination(seat, admin, phaseDestination, available, servers);
    }

    /**
     * Returns an admin's seat when it names a registered backend that is not a waiting room, else the phase's.
     *
     * @param seat where they were, or {@code null} for every login that is not the far end of a swap
     */
    static String destination(
            final @Nullable String seat,
            final boolean admin,
            final String phaseDestination,
            final Set<String> available,
            final PhaseServers servers) {
        if (seat == null || !admin) {
            return phaseDestination;
        }
        if (!available.contains(seat) || servers.isWaitingRoom(seat)) {
            return phaseDestination;
        }
        return seat;
    }
}
