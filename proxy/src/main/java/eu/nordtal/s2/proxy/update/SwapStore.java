package eu.nordtal.s2.proxy.update;

import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Where a proxy swap keeps the two things it cannot keep in memory (season-2-ops/121): which backend
 * each player was standing on, and whether the standby still has anybody.
 *
 * <p>An interface with one implementation, the same shape {@code PlaytimeStore} has and for the same
 * reason: {@link ProxySwap} and {@link StandbyReturn} own the interesting part - when to move
 * somebody - and that part is assertable without a database only if the storage is a seam.</p>
 */
public interface SwapStore {

    /**
     * How long a seat is worth acting on.
     *
     * <p>A swap is over in twenty seconds; this is three minutes, which is the difference between
     * "long enough for a bad one" and "long enough to still be here tomorrow". The upper bound is
     * the one that matters: past it a seat is a player's <em>old</em> position, and acting on one
     * would move somebody on an ordinary login to a server the phase does not point at - which
     * looks exactly like broken routing and is not.</p>
     */
    Duration SEAT_VALID_FOR = Duration.ofMinutes(3);

    /**
     * @param dataSource the proxy's own pool, the same one the access directory borrows
     * @return a store over that pool; it owns nothing and there is nothing to close
     */
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
     * @param server     the backend, as {@code velocity.toml} spells it
     * @param recordedAt when it was written - the only thing that tells a seat from this run apart
     *                   from one left over by a swap nobody came back from
     */
    record Seat(UUID playerUuid, String server, Instant recordedAt) {

        public Seat {
            Objects.requireNonNull(playerUuid, "playerUuid");
            Objects.requireNonNull(server, "server");
            Objects.requireNonNull(recordedAt, "recordedAt");
        }

        /**
         * @param now the proxy's clock
         * @return whether this seat is still worth acting on - see {@link #SEAT_VALID_FOR}
         */
        public boolean isFresh(final Instant now) {
            return !recordedAt.isBefore(now.minus(SEAT_VALID_FOR));
        }
    }

    /**
     * Records where a player is standing, just before they are transferred away.
     *
     * @param player the player
     * @param server the backend they are on, as {@code velocity.toml} spells it
     * @param now    the proxy's clock
     */
    void seat(UUID player, String server, Instant now);

    /**
     * Takes every seat and leaves the table empty - read once, at startup.
     *
     * @return every row there was, stale ones included; {@link Seat#isFresh} is what sorts them
     */
    List<Seat> takeAllSeats();

    /**
     * The standby's heartbeat, so {@code steward-worker} can see whether it is safe to stop it.
     *
     * @param players how many the standby is holding right now; zero is a real answer and the one
     *                the worker is waiting for
     * @param now     the proxy's clock - what makes that zero trustworthy rather than merely the
     *                last thing a dead process happened to write
     */
    void reportStandby(int players, Instant now);
}
