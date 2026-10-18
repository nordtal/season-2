package eu.nordtal.season.steward.stack;

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

/** Reads the player counts and names for {@code /api/services} by compose service name. */
public final class ServicesApi {

    /** The age after which a row counts as absent. */
    static final Duration STALE_AFTER = OnlineDirectory.WRITE_INTERVAL.multipliedBy(3);

    /** The subject of the network total and of the whole player list. */
    static final String PROXY = "proxy";

    /** Orders players by name, then by uuid. */
    private static final Comparator<OnlinePlayer> BY_NAME = Comparator.comparing(
                    (OnlinePlayer player) -> player.name().toLowerCase(Locale.ROOT))
            .thenComparing(player -> player.uuid().toString());

    private final OnlineDirectory online;
    private final OnlineRoster roster;
    private final Clock clock;

    public ServicesApi(final OnlineDirectory online, final OnlineRoster roster, final Clock clock) {
        this.online = Objects.requireNonNull(online, "online");
        this.roster = Objects.requireNonNull(roster, "roster");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Reads both tables at one instant.
     *
     * @return the counts and lists for every subject with a fresh row
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

    /** Returns the fresh players under {@value #PROXY} and under their backend, sorted by name. */
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
     * @param counts the player count by subject, and a missing key is unknown
     * @param roster the players by subject, and a missing key has no list
     */
    public record Online(Map<String, Integer> counts, Map<String, List<OnlinePlayer>> roster) {

        public static final Online NONE = new Online(Map.of(), Map.of());

        public Online {
            counts = Map.copyOf(counts);
            final Map<String, List<OnlinePlayer>> frozen = new LinkedHashMap<>();
            roster.forEach((subject, players) -> frozen.put(subject, List.copyOf(players)));
            roster = Map.copyOf(frozen);
        }
    }
}
