package eu.nordtal.season.stewardagent.run;

import eu.nordtal.season.database.online.OnlineCount;
import eu.nordtal.season.database.online.OnlineDirectory;
import eu.nordtal.season.database.online.OnlinePlayer;
import eu.nordtal.season.database.online.OnlineRoster;
import eu.nordtal.season.database.update.StandbyDirectory;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;
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

    /** One missed roster write at the proxy's usual pace, which it keeps while no run is about to stop a server. */
    Duration ROSTER_FRESH_WITHIN = OnlineDirectory.WRITE_INTERVAL.multipliedBy(2);

    /**
     * Reads one service's player count.
     *
     * @param service a compose service name
     * @param since a row written before this does not count, however fresh
     * @return the count, or empty when no fresh row says
     */
    OptionalInt on(String service, Instant now, Instant since);

    /**
     * Returns the service each connected player is on, by player, at the pace the proxy writes its roster.
     *
     * @return the players on a backend, empty when no fresh roster says
     */
    default Map<UUID, String> roster(final Instant now) {
        return Map.of();
    }

    /** Returns whether a row written at {@code updated} still says something at {@code now}. */
    static boolean counts(final Instant updated, final Instant now, final Instant since) {
        return !updated.isBefore(since) && !updated.isBefore(now.minus(COUNT_FRESH_WITHIN));
    }

    /**
     * Reads the standby proxy's player count.
     *
     * @return the count, or empty when it has not written recently
     */
    OptionalInt onStandbyProxy(Instant now);

    /** Answers empty for every service. */
    Occupancy NONE = new Occupancy() {

        @Override
        public OptionalInt on(final String service, final Instant now, final Instant since) {
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
            private volatile @Nullable OnlineRoster players;

            @Override
            public OptionalInt on(final String service, final Instant now, final Instant since) {
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
                if (count == null || !Occupancy.counts(count.updated(), now, since)) {
                    return OptionalInt.empty();
                }
                return OptionalInt.of(count.players());
            }

            @Override
            public Map<UUID, String> roster(final Instant now) {
                if (players == null) {
                    players = OnlineRoster.using(dataSource, clock);
                }
                final Map<UUID, String> on = new LinkedHashMap<>();
                try {
                    for (final OnlinePlayer player : players.current()) {
                        if (!player.updated().isBefore(now.minus(ROSTER_FRESH_WITHIN))) {
                            player.on().ifPresent(service -> on.put(player.uuid(), service));
                        }
                    }
                } catch (final RuntimeException failure) {
                    return Map.of();
                }
                return on;
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
