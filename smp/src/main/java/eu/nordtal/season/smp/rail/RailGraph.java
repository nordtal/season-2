package eu.nordtal.season.smp.rail;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * One network as a graph: its stations and junctions are the nodes, the track between them the edges.
 * A junction is a rail that three or more connected rails lead into. Track that leaves a node and ends is a spur,
 * which a cart rides only back to that node; track that meets a station rail from the side is neither.
 */
public final class RailGraph {

    private final Set<RailPos> stations;
    private final Set<RailPos> junctions;
    private final List<RailEdge> edges;
    private final Map<RailPos, List<RailEdge>> out = new HashMap<>();
    private final Map<RailPos, RailEdge> spurs;

    public RailGraph(
            final Collection<RailPos> stations, final Collection<RailPos> junctions, final List<RailEdge> edges) {
        this(stations, junctions, edges, Map.of());
    }

    private RailGraph(
            final Collection<RailPos> stations,
            final Collection<RailPos> junctions,
            final List<RailEdge> edges,
            final Map<RailPos, RailEdge> spurs) {
        this.spurs = Map.copyOf(spurs);
        this.stations = Collections.unmodifiableSet(new TreeSet<>(stations));
        this.junctions = Collections.unmodifiableSet(new TreeSet<>(junctions));
        this.edges = List.copyOf(edges);
        for (final RailEdge edge : this.edges) {
            out.computeIfAbsent(edge.from(), _ -> new ArrayList<>()).add(edge);
            out.computeIfAbsent(edge.to(), _ -> new ArrayList<>()).add(edge.reversed());
        }
    }

    /** Builds the graph of the network {@code rails}, in which {@code stations} are the station rails. */
    public static RailGraph of(final Map<RailPos, RailShape> rails, final Collection<RailPos> stations) {
        final RailView view = new RailView() {
            @Override
            public boolean readable(final RailPos pos) {
                return true;
            }

            @Override
            public @Nullable RailShape shape(final RailPos pos) {
                return rails.get(pos);
            }
        };
        final Map<RailPos, List<RailPos>> neighbours = new HashMap<>();
        rails.forEach((pos, shape) -> neighbours.put(pos, Connections.of(pos, shape, view, new ArrayList<>())));
        final Set<RailPos> onTrack = new TreeSet<>(stations);
        onTrack.retainAll(rails.keySet());
        final Set<RailPos> junctions = new TreeSet<>();
        neighbours.forEach((pos, next) -> {
            if (next.size() >= 3) {
                junctions.add(pos);
            }
        });
        final Set<RailPos> nodes = new TreeSet<>(onTrack);
        nodes.addAll(junctions);
        final List<RailEdge> edges = new ArrayList<>();
        final Map<RailPos, RailEdge> spurs = new HashMap<>();
        for (final RailPos node : nodes) {
            for (final RailPos next : neighbours.getOrDefault(node, List.of())) {
                final Stretch stretch = follow(node, next, nodes, neighbours, rails.size());
                final @Nullable RailEdge edge = stretch.edge(node);
                if (stretch.deadEnd() && along(node, next, rails, onTrack)) {
                    spurs.putAll(stretch.spur(node));
                }
                if (edge != null
                        && keeps(edge)
                        && along(edge.from(), edge.first(), rails, onTrack)
                        && along(edge.to(), edge.last(), rails, onTrack)) {
                    edges.add(edge);
                }
            }
        }
        junctions.removeAll(onTrack);
        return new RailGraph(onTrack, junctions, edges, spurs);
    }

    /**
     * The track followed from a node.
     *
     * @param end     the node it reaches, or null when it ends or branches first
     * @param deadEnd whether it ends without reaching a node
     */
    private record Stretch(List<RailPos> between, @Nullable RailPos end, boolean deadEnd) {

        @Nullable
        RailEdge edge(final RailPos node) {
            return end == null ? null : RailEdge.of(node, end, between);
        }

        /** Returns, for each rail of a dead end, the way back from it to {@code node}. */
        Map<RailPos, RailEdge> spur(final RailPos node) {
            final Map<RailPos, RailEdge> back = new HashMap<>();
            for (int index = 0; index < between.size(); index++) {
                back.put(
                        between.get(index),
                        RailEdge.of(
                                between.get(index),
                                node,
                                between.subList(0, index).reversed()));
            }
            return back;
        }
    }

    /** Follows the track from {@code node} through {@code next} until it reaches the next node or ends. */
    private static Stretch follow(
            final RailPos node,
            final RailPos next,
            final Set<RailPos> nodes,
            final Map<RailPos, List<RailPos>> neighbours,
            final int bound) {
        final List<RailPos> between = new ArrayList<>();
        RailPos previous = node;
        RailPos at = next;
        while (!nodes.contains(at)) {
            if (between.size() > bound) {
                return new Stretch(between, null, false);
            }
            between.add(at);
            final RailPos from = previous;
            final List<RailPos> onward = neighbours.getOrDefault(at, List.of()).stream()
                    .filter(pos -> !pos.equals(from))
                    .toList();
            if (onward.size() != 1) {
                return new Stretch(between, null, onward.isEmpty());
            }
            previous = at;
            at = onward.getFirst();
        }
        return new Stretch(between, at, false);
    }

    /** Whether track that leaves {@code node} through {@code next} runs along it, as it must at a station rail. */
    private static boolean along(
            final RailPos node, final RailPos next, final Map<RailPos, RailShape> rails, final Set<RailPos> stations) {
        final @Nullable RailShape shape = rails.get(node);
        return !stations.contains(node) || shape == null || shape.leadsTowards(Heading.between(node, next));
    }

    /** Each edge is found from both of its ends; this keeps one of the two. */
    private static boolean keeps(final RailEdge edge) {
        final int ends = edge.from().compareTo(edge.to());
        if (ends != 0) {
            return ends < 0;
        }
        return edge.first().compareTo(edge.last()) < 0;
    }

    public Set<RailPos> stations() {
        return stations;
    }

    public Set<RailPos> junctions() {
        return junctions;
    }

    public List<RailEdge> edges() {
        return edges;
    }

    /** Returns every rail of the graph's nodes and edges. */
    public Set<RailPos> rails() {
        final Set<RailPos> rails = new TreeSet<>(stations);
        rails.addAll(junctions);
        edges.forEach(edge -> rails.addAll(edge.rails()));
        return rails;
    }

    /** Returns the way back from {@code rail} on a spur to the node the spur leaves, or null off every spur. */
    @Nullable
    RailEdge spur(final RailPos rail) {
        return spurs.get(rail);
    }

    /** Returns the edges that leave {@code node}, each walked from it. */
    List<RailEdge> leaving(final RailPos node) {
        return Objects.requireNonNullElse(out.get(node), List.of());
    }
}
