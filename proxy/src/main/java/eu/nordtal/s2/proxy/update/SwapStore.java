package eu.nordtal.s2.proxy.update;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;

/** Where a proxy swap keeps each player's backend and the standby's head count, outside memory. */
public interface SwapStore {

    /** How long a seat is worth acting on; past it, a seat would override routing on an ordinary login. */
    Duration SEAT_VALID_FOR = Duration.ofMinutes(3);

    /** Returns a store over the proxy's own pool; it owns nothing and there is nothing to close. */
    static SwapStore using(final DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        final SwapDao dao = Jdbi.create(dataSource)
                .installPlugin(new SqlObjectPlugin())
                .installPlugin(new PostgresPlugin())
                .onDemand(SwapDao.class);
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
     * One row of {@code proxy_swap_seat}: where somebody was standing when they were parked.
     *
     * @param playerUuid the player
     * @param server the backend, as {@code velocity.toml} spells it
     * @param recordedAt when it was written, which tells this run's seats from leftovers
     */
    record Seat(UUID playerUuid, String server, Instant recordedAt) {

        public Seat {
            Objects.requireNonNull(playerUuid, "playerUuid");
            Objects.requireNonNull(server, "server");
            Objects.requireNonNull(recordedAt, "recordedAt");
        }

        /** Returns whether this seat is still worth acting on, by {@link #SEAT_VALID_FOR}. */
        public boolean isFresh(final Instant now) {
            return !recordedAt.isBefore(now.minus(SEAT_VALID_FOR));
        }
    }

    /**
     * Records where a player is standing, just before they are transferred away.
     *
     * @param player the player
     * @param server the backend they are on, as {@code velocity.toml} spells it
     * @param now the proxy's clock
     */
    void seat(UUID player, String server, Instant now);

    /**
     * Takes every seat and leaves the table empty; read once, at startup.
     *
     * @return every row there was, stale ones included
     */
    List<Seat> takeAllSeats();

    /**
     * The standby's heartbeat, so {@code steward-worker} can see whether it is safe to stop it.
     *
     * @param players how many the standby is holding right now; zero is what the worker waits for
     * @param now the proxy's clock, which tells a live zero from a dead process's last write
     */
    void reportStandby(int players, Instant now);
}
