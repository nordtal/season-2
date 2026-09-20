package eu.nordtal.s2.steward.worker.serve;

import eu.nordtal.s2.common.online.OnlineCount;
import eu.nordtal.s2.common.online.OnlineDirectory;
import eu.nordtal.s2.common.update.StandbyDirectory;

import org.jetbrains.annotations.NotNull;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.OptionalInt;

/**
 * How many players are on a service <em>right now</em>, as a seam (season-2-ops/122).
 *
 * <h2>Why "nobody said" is a third answer and not a zero</h2>
 * The run waits for a service to be free of players before it stops it, and then stops it anyway
 * after ten seconds. Both halves of that need to tell "there is nobody on it" apart from "nothing
 * has told me for a while": the first is the outcome the wait exists for, the second is a proxy
 * that has stopped writing, and folding the two together would end the wait early on the exact run
 * where waiting mattered. So every answer here is an {@link OptionalInt}, empty means nobody said
 * recently, and the caller has to write down which of the two it acted on.
 *
 * <h2>Freshness is decided here, once</h2>
 * {@code OnlineDirectory} and {@code StandbyDirectory} both hand back a number with the instant it
 * was written and deliberately no opinion about it. This is where the opinion lives, and the two
 * cutoffs differ because the two cadences do: the proxy writes {@code online_count} every second
 * while a run has something moving (see {@code OnlineWriter}) and the standby writes its own row
 * every two.
 */
interface Occupancy {

    /**
     * How stale an {@code online_count} row may be and still be acted on.
     *
     * <p>Four seconds: three missed ticks of the one-second cadence the proxy switches to while a
     * run is moving something. Wider than that and a ten-second wait would be deciding on a number
     * from before the players were moved, which is the failure this whole seam exists to avoid.</p>
     */
    Duration COUNT_FRESH_WITHIN = Duration.ofSeconds(4);

    /** Four missed heartbeats of {@code StandbyReturn.INTERVAL}, for the same reasoning. */
    Duration STANDBY_FRESH_WITHIN = Duration.ofSeconds(8);

    /**
     * @param service a compose service name
     * @param now     the run's clock
     * @return how many players are on it, or empty when no fresh row says
     */
    @NotNull OptionalInt on(@NotNull String service, @NotNull Instant now);

    /**
     * @return how many players the standby proxy is holding, or empty when it has never written a
     *         row or has stopped writing. A standby nobody has heard from is not an empty one
     */
    @NotNull OptionalInt onStandbyProxy(@NotNull Instant now);

    /** Nothing to read. Every answer is empty, which reads as "nobody said" everywhere. */
    Occupancy NONE = new Occupancy() {

        @Override
        public @NotNull OptionalInt on(final @NotNull String service, final @NotNull Instant now) {
            return OptionalInt.empty();
        }

        @Override
        public @NotNull OptionalInt onStandbyProxy(final @NotNull Instant now) {
            return OptionalInt.empty();
        }
    };

    /**
     * The production reader, over the pool this process already owns.
     *
     * <p>The two directories are built on first use rather than in this method, because a run that
     * never opens a standby window never asks either of them, and a constructor that opened a
     * connection would make every unit-level construction of {@code Runner} need a database.</p>
     */
    static @NotNull Occupancy over(final @NotNull DataSource dataSource) {
        return new Occupancy() {

            private volatile OnlineDirectory counts;
            private volatile StandbyDirectory standby;

            @Override
            public @NotNull OptionalInt on(final @NotNull String service,
                                           final @NotNull Instant now) {
                if (counts == null) {
                    counts = OnlineDirectory.using(dataSource);
                }
                final Map<String, OnlineCount> current;
                try {
                    current = counts.current();
                } catch (final RuntimeException failure) {
                    // A database this run cannot read is a "nobody said", not a zero. The run has
                    // its own cap and its own report line for that, and both of them are honest.
                    return OptionalInt.empty();
                }
                final OnlineCount count = current.get(service);
                if (count == null || count.updated().isBefore(now.minus(COUNT_FRESH_WITHIN))) {
                    return OptionalInt.empty();
                }
                return OptionalInt.of(count.players());
            }

            @Override
            public @NotNull OptionalInt onStandbyProxy(final @NotNull Instant now) {
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
