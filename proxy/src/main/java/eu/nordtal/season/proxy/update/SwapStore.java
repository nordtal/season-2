package eu.nordtal.season.proxy.update;

import eu.nordtal.season.database.Jdbis;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

/** Stores the server of each player and the player count of the standby proxy. */
public interface SwapStore {

    /** The time for which a seat is valid. */
    Duration SEAT_VALID_FOR = Duration.ofMinutes(3);

    /** Returns a store over the pool of the proxy. */
    static SwapStore using(final DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        final SwapDao dao = Jdbis.over(dataSource).onDemand(SwapDao.class);
        return new SwapStore() {

            @Override
            public void seat(final UUID player, final String server, final Instant now) {
                dao.seat(player, server, now);
            }

            @Override
            public List<Seat> takeAllSeats() {
                return dao.takeAllSeats();
            }

            @Override
            public void reportStandby(final int players, final Instant now) {
                dao.reportStandby(players, now);
            }
        };
    }

    /**
     * A row of {@code proxy_swap_seat}.
     *
     * @param server the server name of {@code velocity.toml}
     * @param recordedAt the time of the write
     */
    record Seat(UUID playerUuid, String server, Instant recordedAt) {

        public Seat {
            Objects.requireNonNull(playerUuid, "playerUuid");
            Objects.requireNonNull(server, "server");
            Objects.requireNonNull(recordedAt, "recordedAt");
        }

        /** Returns whether the seat is younger than {@link #SEAT_VALID_FOR}. */
        public boolean isFresh(final Instant now) {
            return !recordedAt.isBefore(now.minus(SEAT_VALID_FOR));
        }
    }

    /**
     * Stores the server of the player before the transfer.
     *
     * @param server the server name of {@code velocity.toml}
     */
    void seat(UUID player, String server, Instant now);

    /** Returns every seat and deletes them. It runs once at startup. */
    List<Seat> takeAllSeats();

    /**
     * Stores the number of players on the standby proxy. Steward stops the standby when the number is zero.
     *
     * @param now the time of the write
     */
    void reportStandby(int players, Instant now);
}
