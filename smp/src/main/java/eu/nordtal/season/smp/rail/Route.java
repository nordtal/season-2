package eu.nordtal.season.smp.rail;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** The shortest way along the rails from one station to another, as the edges walked in order. */
public record Route(List<RailEdge> legs) {

    public Route {
        legs = List.copyOf(legs);
    }

    /** Returns the shortest route from {@code from} to {@code to} in {@code graph}, or empty when none exists. */
    public static Optional<Route> between(final RailGraph graph, final RailPos from, final RailPos to) {
        final Map<RailPos, RailEdge> reachedBy = new HashMap<>();
        final Map<RailPos, Integer> distance = walk(graph, from, reachedBy);
        if (!distance.containsKey(to) || from.equals(to)) {
            return Optional.empty();
        }
        final ArrayDeque<RailEdge> legs = new ArrayDeque<>();
        RailPos at = to;
        while (!at.equals(from)) {
            final RailEdge leg = reachedBy.get(at);
            if (leg == null) {
                return Optional.empty();
            }
            legs.addFirst(leg);
            at = leg.from();
        }
        return Optional.of(new Route(new ArrayList<>(legs)));
    }

    /** Returns the distance along the rails from {@code from} to every node it reaches, itself included. */
    public static Map<RailPos, Integer> distances(final RailGraph graph, final RailPos from) {
        return walk(graph, from, new HashMap<>());
    }

    private record Reach(RailPos node, int distance) {}

    private static Map<RailPos, Integer> walk(
            final RailGraph graph, final RailPos from, final Map<RailPos, RailEdge> reachedBy) {
        final Map<RailPos, Integer> distance = new HashMap<>();
        final PriorityQueue<Reach> queue = new PriorityQueue<>((a, b) -> a.distance() != b.distance()
                ? Integer.compare(a.distance(), b.distance())
                : a.node().compareTo(b.node()));
        distance.put(from, 0);
        queue.add(new Reach(from, 0));
        final Set<RailPos> stations = graph.stations();
        while (!queue.isEmpty()) {
            final Reach reach = queue.poll();
            if (reach.distance() > distance.getOrDefault(reach.node(), Integer.MAX_VALUE)) {
                continue;
            }
            final @Nullable RailEdge arrival = reachedBy.get(reach.node());
            for (final RailEdge edge : graph.leaving(reach.node())) {
                if (arrival != null && stations.contains(reach.node()) && !straightOn(arrival, edge)) {
                    // A station rail is straight and never switched, so a cart only passes through along it.
                    continue;
                }
                final int through = reach.distance() + edge.length();
                if (through < distance.getOrDefault(edge.to(), Integer.MAX_VALUE)) {
                    distance.put(edge.to(), through);
                    reachedBy.put(edge.to(), edge);
                    queue.add(new Reach(edge.to(), through));
                }
            }
        }
        return distance;
    }

    private static boolean straightOn(final RailEdge arrival, final RailEdge onward) {
        final RailPos station = onward.from();
        return Heading.between(station, arrival.last())
                == Heading.between(station, onward.first()).opposite();
    }

    public int length() {
        return legs.stream().mapToInt(RailEdge::length).sum();
    }

    /** Returns the shape each junction on the way must have for the cart to take its next leg, in route order. */
    public Map<RailPos, RailShape> switches(final Set<RailPos> junctions) {
        final Map<RailPos, RailShape> switches = new LinkedHashMap<>();
        for (int leg = 1; leg < legs.size(); leg++) {
            final RailEdge in = legs.get(leg - 1);
            final RailEdge onward = legs.get(leg);
            final RailPos junction = onward.from();
            if (!junctions.contains(junction)) {
                continue;
            }
            final RailPos before = in.last();
            final RailPos after = onward.first();
            final Heading back = Heading.between(junction, before);
            final Heading ahead = Heading.between(junction, after);
            if (back == ahead) {
                continue;
            }
            switches.put(junction, RailShape.joining(back, ahead, rising(junction, before, back, after, ahead)));
        }
        return switches;
    }

    private static @Nullable Heading rising(
            final RailPos junction,
            final RailPos before,
            final Heading back,
            final RailPos after,
            final Heading ahead) {
        if (after.y() > junction.y()) {
            return ahead;
        }
        return before.y() > junction.y() ? back : null;
    }
}
