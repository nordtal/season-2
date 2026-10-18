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
 * Holds the server of each player from before a proxy swap. It is filled once before Velocity binds. Only the seat of
 * an admin is used.
 */
public final class ParkedSeats {

    private final Map<UUID, String> seats = new ConcurrentHashMap<>();

    /**
     * Keeps the seats that are younger than {@link SwapStore#SEAT_VALID_FOR}.
     *
     * @param rows the rows of {@link SwapStore#takeAllSeats()}
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

    /** Returns whether the player has a seat. */
    public boolean holds(final UUID player) {
        return seats.containsKey(player);
    }

    /** Returns the number of seats. */
    public int size() {
        return seats.size();
    }

    /**
     * Returns the server for the released player and deletes the seat.
     *
     * @param phaseDestination the server from {@code PhaseRouting#decideRelease}
     * @param available the registered servers
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
     * Returns the seat if the player is an admin and the seat is a registered server that is not a waiting room,
     * otherwise the server of the phase.
     *
     * @param seat the server before the swap, {@code null} without a swap
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
