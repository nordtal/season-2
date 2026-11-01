package eu.nordtal.season.smp.rail;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** The stations a rider can pick, in the order the ride menu lists them. */
final class RideList {

    /** A station the menu lists, with its distance along the rails from the cart. */
    record Stop(UUID station, String name, int distance, boolean favourite) {}

    private RideList() {}

    /**
     * Lists the named stations a cart at {@code at} reaches: favourites first, then the rest, each by distance.
     * The station the cart stands on and the stations it cannot reach are left out.
     */
    static List<Stop> of(
            final RailGraph graph,
            final RailPos at,
            final Collection<StationRow> stations,
            final Set<UUID> favourites) {
        final Map<RailPos, Integer> distances = distances(graph, at).orElse(Map.of());
        final List<Stop> stops = new ArrayList<>();
        for (final StationRow station : stations) {
            final @Nullable String name = station.name();
            final @Nullable Integer distance = distances.get(station.pos());
            if (name != null && distance != null && !station.pos().equals(at)) {
                stops.add(new Stop(station.id(), name, distance, favourites.contains(station.id())));
            }
        }
        stops.sort(Comparator.comparing((Stop stop) -> !stop.favourite())
                .thenComparingInt(Stop::distance)
                .thenComparing(stop -> stop.name().toLowerCase(Locale.ROOT))
                .thenComparing(Stop::station));
        return List.copyOf(stops);
    }

    /** Returns whether {@code at} is a node of {@code graph} or a rail of one of its edges. */
    static boolean onTrack(final RailGraph graph, final RailPos at) {
        return distances(graph, at).isPresent();
    }

    /** Returns the distance from {@code at} to every node it reaches, or empty when {@code at} is on no track. */
    private static Optional<Map<RailPos, Integer>> distances(final RailGraph graph, final RailPos at) {
        if (graph.stations().contains(at) || graph.junctions().contains(at)) {
            return Optional.of(Route.distances(graph, at));
        }
        for (final RailEdge edge : graph.edges()) {
            final int index = edge.rails().indexOf(at);
            if (index < 0) {
                continue;
            }
            // A cart between two nodes rides to either end first, whichever way is shorter to the station.
            final Map<RailPos, Integer> shortest = new HashMap<>();
            merge(shortest, Route.distances(graph, edge.from()), index + 1);
            merge(shortest, Route.distances(graph, edge.to()), edge.length() - index - 1);
            return Optional.of(shortest);
        }
        return Optional.empty();
    }

    private static void merge(final Map<RailPos, Integer> into, final Map<RailPos, Integer> from, final int offset) {
        from.forEach((node, distance) -> into.merge(node, distance + offset, Math::min));
    }
}
