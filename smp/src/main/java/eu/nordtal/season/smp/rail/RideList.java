package eu.nordtal.season.smp.rail;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/** The stations a rider can pick, in the order the ride menu lists them. */
final class RideList {

    /** A station the menu lists, with its distance along the rails from the cart. */
    record Stop(UUID station, String name, int distance, boolean favourite) {}

    private RideList() {}

    /**
     * Lists the stations a cart at {@code at} reaches: the spawn, then favourites, then the rest, by distance.
     * The spawn is called {@code spawnName}. Stations within {@code near} blocks and unreachable ones are left out.
     */
    static List<Stop> of(
            final RailGraph graph,
            final RailPos at,
            final Collection<StationRow> stations,
            final Set<UUID> favourites,
            final int near,
            final String spawnName) {
        final Map<RailPos, Integer> distances = distances(graph, at).orElse(Map.of());
        final Set<UUID> spawns =
                stations.stream().filter(StationRow::spawn).map(StationRow::id).collect(Collectors.toSet());
        final List<Stop> stops = new ArrayList<>();
        for (final StationRow station : stations) {
            final @Nullable String name = station.spawn() ? spawnName : station.name();
            final @Nullable Integer distance = distances.get(station.pos());
            if (name != null && distance != null && distance > near) {
                stops.add(new Stop(station.id(), name, distance, favourites.contains(station.id())));
            }
        }
        stops.sort(Comparator.comparing((Stop stop) -> !spawns.contains(stop.station()))
                .thenComparing(stop -> !stop.favourite())
                .thenComparingInt(Stop::distance)
                .thenComparing(stop -> stop.name().toLowerCase(Locale.ROOT))
                .thenComparing(Stop::station));
        return List.copyOf(stops);
    }

    /** Returns whether {@code at} is a node of {@code graph}, a rail of one of its edges or a rail of a spur. */
    static boolean onTrack(final RailGraph graph, final RailPos at) {
        return graph.spur(at) != null
                || graph.stations().contains(at)
                || graph.junctions().contains(at)
                || graph.edges().stream().anyMatch(edge -> edge.rails().contains(at));
    }

    /** Returns the distance from {@code at} to every node it reaches, or empty when {@code at} is on no track. */
    private static Optional<Map<RailPos, Integer>> distances(final RailGraph graph, final RailPos at) {
        return onTrack(graph, at) ? Optional.of(Route.distances(graph, at)) : Optional.empty();
    }
}
