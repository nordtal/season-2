package eu.nordtal.season.smp.rail;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/** Which rail networks the map draws: a network with fewer than two stations that are not hidden is left off. */
final class NetworkMap {

    /** The fewest stations not hidden that a network shows on the map with. */
    static final int LEAST_SHOWN = 2;

    private NetworkMap() {}

    /** Whether the map draws a network of {@code stations}, of which those in {@code hidden} are hidden. */
    static boolean drawn(final Collection<StationRow> stations, final Set<UUID> hidden) {
        return stations.stream()
                        .filter(station -> !hidden.contains(station.id()))
                        .count()
                >= LEAST_SHOWN;
    }
}
