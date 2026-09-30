package eu.nordtal.s2.steward.worker.serve;

import eu.nordtal.s2.database.online.OnlineCount;
import eu.nordtal.s2.database.online.OnlineDirectory;
import eu.nordtal.s2.database.update.StandbyDirectory;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Map;
import java.util.OptionalInt;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;

/**
 * How many players are on a service right now, where empty means no fresh row said, never zero.
 *
 * Freshness is decided here once; the two cutoffs differ because the proxy and standby write at different rates.
 */
interface Occupancy {

    /** How stale an {@code online_count} row may be and still be acted on: three missed one-second ticks. */
    Duration COUNT_FRESH_WITHIN = Duration.ofSeconds(4);

    /** Four missed heartbeats of {@code StandbyReturn.INTERVAL}. */
    Duration STANDBY_FRESH_WITHIN = Duration.ofSeconds(8);

    /**
     * Reads one service's player count.
     *
     * @param service a compose service name
     * @param now the run's clock
     * @return how many players are on it, or empty when no fresh row says
     */
    OptionalInt on(String service, Instant now);

    /**
     * Reads the standby proxy's player count.
     *
     * @return how many players it holds, or empty when it has not written recently, which is not zero
     */
    OptionalInt onStandbyProxy(Instant now);

    /** Nothing to read: every answer is empty, which reads as "nobody said". */
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

    /** The production reader, over the pool this process already owns, opening its directories on first use. */
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
                    // A database this run cannot read is a "nobody said", not a zero.
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
