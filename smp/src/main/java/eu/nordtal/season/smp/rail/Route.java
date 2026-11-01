package eu.nordtal.season.smp.rail;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The shortest way a cart can ride along the rails from one rail to a station, as the edges walked in order.
 * The first leg may start on a rail between two nodes. A cart passes a station rail only along it, never turns
 * back at a node, and takes no turn at a junction onto or off a rail one block higher, since a curve is flat.
 */
public record Route(List<RailEdge> legs) {

    public Route {
        legs = List.copyOf(legs);
    }

    /** Returns the shortest route from the node {@code from} to {@code to} in {@code graph}, or empty when none. */
    public static Optional<Route> between(final RailGraph graph, final RailPos from, final RailPos to) {
        return between(graph, from, null, to);
    }

    /**
     * Returns the shortest route from the rail {@code from} to the node {@code to}, or empty when none exists.
     *
     * @param ahead the rail the cart must ride onto first, when it is moving; null when it stands and may go either way
     */
    public static Optional<Route> between(
            final RailGraph graph, final RailPos from, final @Nullable RailPos ahead, final RailPos to) {
        if (from.equals(to)) {
            return Optional.empty();
        }
        final Search search = new Search(graph);
        search.run(starts(graph, from, ahead));
        @Nullable RailEdge best = null;
        int shortest = Integer.MAX_VALUE;
        for (final Map.Entry<RailEdge, Integer> reached : search.distance.entrySet()) {
            if (reached.getKey().to().equals(to) && reached.getValue() < shortest) {
                best = reached.getKey();
                shortest = reached.getValue();
            }
        }
        if (best == null) {
            return Optional.empty();
        }
        final ArrayDeque<RailEdge> legs = new ArrayDeque<>();
        for (@Nullable RailEdge leg = best; leg != null; leg = search.previous.get(leg)) {
            legs.addFirst(leg);
        }
        return Optional.of(new Route(new ArrayList<>(legs)));
    }

    /** Returns the distance a cart standing on {@code from} rides to every node it reaches, itself included. */
    public static Map<RailPos, Integer> distances(final RailGraph graph, final RailPos from) {
        final Search search = new Search(graph);
        search.run(starts(graph, from, null));
        final Map<RailPos, Integer> distances = new HashMap<>();
        if (graph.stations().contains(from) || graph.junctions().contains(from)) {
            distances.put(from, 0);
        }
        search.distance.forEach((edge, distance) -> distances.merge(edge.to(), distance, Math::min));
        return distances;
    }

    /** Returns the first legs a cart on {@code from} can take: every edge of a node, or both ways along an edge. */
    private static List<RailEdge> starts(final RailGraph graph, final RailPos from, final @Nullable RailPos ahead) {
        final List<RailEdge> starts = new ArrayList<>();
        if (graph.stations().contains(from) || graph.junctions().contains(from)) {
            starts.addAll(graph.leaving(from));
        } else {
            for (final RailEdge edge : graph.edges()) {
                final int index = edge.rails().indexOf(from);
                if (index >= 0) {
                    final List<RailPos> rails = edge.rails();
                    starts.add(new RailEdge(
                            from, edge.to(), rails.subList(index + 1, rails.size()), edge.length() - index - 1));
                    starts.add(new RailEdge(
                            from, edge.from(), rails.subList(0, index).reversed(), index + 1));
                    break;
                }
            }
        }
        starts.removeIf(start -> ahead != null && !start.first().equals(ahead));
        return starts;
    }

    /** A shortest path search whose states are the edges a cart arrived by, so that a turn can be refused. */
    private static final class Search {

        private record Reach(RailEdge by, int distance, long order) {}

        private final RailGraph graph;
        private final IdentityHashMap<RailEdge, Integer> distance = new IdentityHashMap<>();
        private final IdentityHashMap<RailEdge, RailEdge> previous = new IdentityHashMap<>();

        Search(final RailGraph graph) {
            this.graph = graph;
        }

        void run(final List<RailEdge> starts) {
            final PriorityQueue<Reach> queue = new PriorityQueue<>((a, b) -> a.distance() != b.distance()
                    ? Integer.compare(a.distance(), b.distance())
                    : Long.compare(a.order(), b.order()));
            long order = 0;
            for (final RailEdge start : starts) {
                distance.put(start, start.length());
                queue.add(new Reach(start, start.length(), order++));
            }
            while (!queue.isEmpty()) {
                final Reach reach = queue.poll();
                if (reach.distance() > distance.getOrDefault(reach.by(), Integer.MAX_VALUE)) {
                    continue;
                }
                for (final RailEdge onward : graph.leaving(reach.by().to())) {
                    final int through = reach.distance() + onward.length();
                    if (passes(graph, reach.by(), onward)
                            && through < distance.getOrDefault(onward, Integer.MAX_VALUE)) {
                        distance.put(onward, through);
                        previous.put(onward, reach.by());
                        queue.add(new Reach(onward, through, order++));
                    }
                }
            }
        }
    }

    /** Whether a cart that arrived at a node by {@code arrival} can ride on along {@code onward}. */
    static boolean passes(final RailGraph graph, final RailEdge arrival, final RailEdge onward) {
        final RailPos node = onward.from();
        final RailPos before = arrival.last();
        final RailPos after = onward.first();
        final Heading back = Heading.between(node, before);
        final Heading ahead = Heading.between(node, after);
        if (back == ahead) {
            return false;
        }
        if (graph.stations().contains(node)) {
            // A station rail is straight and never switched, so a cart only passes through along it.
            return ahead == back.opposite();
        }
        if (before.y() > node.y() && after.y() > node.y()) {
            return false;
        }
        return ahead == back.opposite() || (before.y() <= node.y() && after.y() <= node.y());
    }

    public int length() {
        return legs.stream().mapToInt(RailEdge::length).sum();
    }

    /** Returns every rail the cart rides over in order, from the rail it starts on to the station it ends at. */
    public List<RailPos> rails() {
        final List<RailPos> rails = new ArrayList<>(length() + 1);
        rails.add(legs.getFirst().from());
        for (final RailEdge leg : legs) {
            rails.addAll(leg.rails());
            rails.add(leg.to());
        }
        return rails;
    }

    /** Returns the shape each junction on the way must have for the cart to take its next leg, in route order. */
    public Map<RailPos, RailShape> switches(final Set<RailPos> junctions) {
        final Map<RailPos, RailShape> switches = new LinkedHashMap<>();
        final RailEdge start = legs.getFirst();
        if (junctions.contains(start.from())) {
            // A cart that starts on a junction comes from no rail, so the junction runs straight on ahead.
            final Heading ahead = Heading.between(start.from(), start.first());
            switches.put(
                    start.from(),
                    RailShape.joining(
                            ahead.opposite(),
                            ahead,
                            start.first().y() > start.from().y() ? ahead : null));
        }
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
