package eu.nordtal.season.smp.rail;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** The rules for a station's name: at most 32 characters, and unique within its network regardless of case. */
public final class StationNames {

    public static final int MAX_LENGTH = 32;

    /** What the rules say about a new name. */
    public enum Verdict {
        ACCEPTED,
        EMPTY,
        TOO_LONG,
        TAKEN
    }

    /** A named station of a network, and when it got its name. */
    public record Named(UUID station, String name, Instant named) {}

    private StationNames() {}

    /** Judges {@code name}, already stripped, against the names of the other stations in the network. */
    public static Verdict judge(final String name, final Collection<String> others) {
        if (name.isBlank()) {
            return Verdict.EMPTY;
        }
        if (name.codePointCount(0, name.length()) > MAX_LENGTH) {
            return Verdict.TOO_LONG;
        }
        final String key = key(name);
        return others.stream().map(StationNames::key).anyMatch(key::equals) ? Verdict.TAKEN : Verdict.ACCEPTED;
    }

    /** Judges the name a player typed for {@code station} against the other named stations of {@code network}. */
    public static Verdict judgeTyped(final String typed, final UUID station, final Collection<StationRow> network) {
        final List<String> others = network.stream()
                .filter(row -> !row.id().equals(station))
                .map(StationRow::name)
                .filter(Objects::nonNull)
                .toList();
        return judge(typed.strip(), others);
    }

    /**
     * Returns the new name of every station whose name clashes after networks merged.
     * The station named first keeps its name, and each one named later gets the lowest free number suffix.
     */
    public static Map<UUID, String> settleClashes(final List<Named> stations) {
        final List<Named> ordered = stations.stream()
                .sorted(Comparator.comparing(Named::named).thenComparing(Named::station))
                .toList();
        final Set<String> taken =
                ordered.stream().map(named -> key(named.name())).collect(Collectors.toSet());
        final Set<String> kept = new HashSet<>();
        final Map<UUID, String> renamed = new HashMap<>();
        for (final Named station : ordered) {
            if (kept.add(key(station.name()))) {
                continue;
            }
            final String name = suffixed(station.name(), taken);
            taken.add(key(name));
            kept.add(key(name));
            renamed.put(station.station(), name);
        }
        return renamed;
    }

    private static String suffixed(final String name, final Set<String> taken) {
        for (int number = 2; ; number++) {
            final String suffix = " " + number;
            final int room = MAX_LENGTH - suffix.length();
            final String base = name.codePointCount(0, name.length()) > room
                    ? name.substring(0, name.offsetByCodePoints(0, room)).strip()
                    : name;
            final String candidate = base + suffix;
            if (!taken.contains(key(candidate))) {
                return candidate;
            }
        }
    }

    private static String key(final String name) {
        return name.strip().toLowerCase(Locale.ROOT);
    }
}
