package eu.nordtal.season.stewardagent.run;

import eu.nordtal.season.database.online.OnlineCount;
import eu.nordtal.season.database.online.OnlineDirectory;
import eu.nordtal.season.database.update.StandbyDirectory;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Map;
import java.util.OptionalInt;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;

/**
 * Returns how many players are on a service. Empty means no fresh row says, never zero.
 *
 * The two cutoffs differ because the proxy and the standby write at different rates.
 */
interface Occupancy {

    /** The age up to which an {@code online_count} row counts: three missed ticks of one second. */
    Duration COUNT_FRESH_WITHIN = Duration.ofSeconds(4);

    /** Four missed heartbeats of {@code StandbyReturn.INTERVAL}. */
    Duration STANDBY_FRESH_WITHIN = Duration.ofSeconds(8);

    /**
     * Reads one service's player count.
     *
     * @param service a compose service name
     * @return the count, or empty when no fresh row says
     */
    OptionalInt on(String service, Instant now);

    /**
     * Reads the standby proxy's player count.
     *
     * @return the count, or empty when it has not written recently
     */
    OptionalInt onStandbyProxy(Instant now);

    /** Answers empty for every service. */
    Occupancy NONE = new Occupancy() {

        @Override
        public OptionalInt on(final String service, final Instant now) {
            return OptionalInt.empty();
        }

        @Override
        public OptionalInt onStandbyProxy(final Instant now) {
            return OptionalInt.empty();
        }
    };

    static Occupancy over(final DataSource dataSource, final InstantSource clock) {
        return new Occupancy() {

            private volatile @Nullable OnlineDirectory counts;
            private volatile @Nullable StandbyDirectory standby;

            @Override
            public OptionalInt on(final String service, final Instant now) {
                if (counts == null) {
                    counts = OnlineDirectory.using(dataSource, clock);
                }
                final Map<String, OnlineCount> current;
                try {
                    current = counts.current();
                } catch (final RuntimeException failure) {
                    // A database this run cannot read gives empty, not zero.
                    return OptionalInt.empty();
                }
                final OnlineCount count = current.get(service);
                if (count == null || count.updated().isBefore(now.minus(COUNT_FRESH_WITHIN))) {
                    return OptionalInt.empty();
                }
                return OptionalInt.of(count.players());
            }

            @Override
            public OptionalInt onStandbyProxy(final Instant now) {
                if (standby == null) {
                    standby = StandbyDirectory.using(dataSource);
                }
                try {
                    return standby.current()
                            .filter(state -> state.isFresh(now, STANDBY_FRESH_WITHIN))
                            .map(state -> OptionalInt.of(state.players()))
                            .orElseGet(OptionalInt::empty);
                } catch (final RuntimeException failure) {
                    return OptionalInt.empty();
                }
            }
        };
    }
}
