package eu.nordtal.season.steward.api;

import eu.nordtal.season.database.online.OnlineCount;
import eu.nordtal.season.database.online.OnlineDirectory;
import eu.nordtal.season.database.online.OnlinePlayer;
import eu.nordtal.season.database.online.OnlineRoster;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Feeds {@code /api/services} player counts and names, keyed by compose service name.
 *
 * A missing key means unknown, never zero; a row older than {@link #STALE_AFTER} counts as missing.
 */
public final class ServicesApi {

    /**
     * How old a row may be before it is treated as absent.
     *
     * Three write intervals: one missed write is noise, three mean the proxy has stopped writing.
     */
    static final Duration STALE_AFTER = OnlineDirectory.WRITE_INTERVAL.multipliedBy(3);

    /** The proxy's own subject: the network total, and the key for the network's whole player list. */
    static final String PROXY = "proxy";

    /** By name, then uuid, so {@code Ada} and {@code ada} keep their order between reads. */
    private static final Comparator<OnlinePlayer> BY_NAME = Comparator.comparing(
                    (OnlinePlayer player) -> player.name().toLowerCase(Locale.ROOT))
            .thenComparing(player -> player.uuid().toString());

    private final OnlineDirectory online;
    private final OnlineRoster roster;
    private final Clock clock;

    /** Package-visible so a test can hold time still. */
    public ServicesApi(final OnlineDirectory online, final OnlineRoster roster, final Clock clock) {
        this.online = Objects.requireNonNull(online, "online");
        this.roster = Objects.requireNonNull(roster, "roster");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Both tables, judged against one instant.
     *
     * @return the counts and lists for every subject with a fresh row, and no key for any other
     */
    public Online read() {
        final Instant now = clock.instant();
        return new Online(freshCounts(now), freshRoster(now));
    }

    private Map<String, Integer> freshCounts(final Instant now) {
        final Map<String, Integer> fresh = new LinkedHashMap<>();
        for (final Map.Entry<String, OnlineCount> entry : online.current().entrySet()) {
            final OnlineCount count = entry.getValue();
            if (isFresh(count.updated(), now)) {
                fresh.put(entry.getKey(), count.players());
            }
        }
        return fresh;
    }

    /**
     * The fresh players, under {@value #PROXY} and under the backend they are on, sorted by name.
     *
     * Sorted, so the first three faces the interface draws do not reshuffle on every refresh.
     */
    private Map<String, List<OnlinePlayer>> freshRoster(final Instant now) {
        final Map<String, List<OnlinePlayer>> bySubject = new LinkedHashMap<>();
        final List<OnlinePlayer> network = new ArrayList<>();
        for (final OnlinePlayer player : roster.current()) {
            if (!isFresh(player.updated(), now)) {
                continue;
            }
            network.add(player);
            player.on()
                    .ifPresent(subject -> bySubject
                            .computeIfAbsent(subject, key -> new ArrayList<>())
                            .add(player));
        }
        if (!network.isEmpty()) {
            bySubject.put(PROXY, network);
        }
        bySubject.values().forEach(players -> players.sort(BY_NAME));
        return bySubject;
    }

    private static boolean isFresh(final Instant updated, final Instant now) {
        return Duration.between(updated, now).compareTo(STALE_AFTER) <= 0;
    }

    /**
     * One reading of both tables.
     *
     * @param counts subject to player count; a missing key is unknown, not zero
     * @param roster subject to the players on it; a missing key has no list, which does not mean nobody is on
     */
    public record Online(Map<String, Integer> counts, Map<String, List<OnlinePlayer>> roster) {

        /** The answer without a database: absence, not zeroes. */
        public static final Online NONE = new Online(Map.of(), Map.of());

        public Online {
            counts = Map.copyOf(counts);
            final Map<String, List<OnlinePlayer>> frozen = new LinkedHashMap<>();
            roster.forEach((subject, players) -> frozen.put(subject, List.copyOf(players)));
            roster = Map.copyOf(frozen);
        }
    }
}
