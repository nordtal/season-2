package eu.nordtal.season.smp.rail;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One network as a graph: its stations and junctions are the nodes, the track between them the edges.
 * A junction is a rail that three or more connected rails lead into; track that leaves a node and ends is a spur.
 * A graph is a snapshot: once its network changes it is no longer {@link #current()}, and a ride plans again.
 */
public final class RailGraph {

    /** Where a rail lies: the leg it is on, and its place among the leg's rails. */
    record Place(RailLeg leg, int index) {}

    /** Finds the place of a rail, among the legs of this graph and maybe others. */
    interface Places {
        @Nullable
        Place place(RailPos rail);
    }

    /** Gathers a graph a node and a leg at a time, so a large one is built over several ticks. */
    static final class Builder {
        private final Set<RailPos> stations = new TreeSet<>();
        private final Set<RailPos> junctions = new TreeSet<>();
        private final Set<RailLeg> legs = Collections.newSetFromMap(new IdentityHashMap<>());
        private final Map<RailPos, List<RailEdge>> out = new HashMap<>();

        void station(final RailPos pos) {
            stations.add(pos);
        }

        void junction(final RailPos pos) {
            junctions.add(pos);
        }

        boolean hasStation() {
            return !stations.isEmpty();
        }

        /** Adds {@code leg}, and returns whether it was not added before. */
        boolean leg(final RailLeg leg) {
            if (!legs.add(leg)) {
                return false;
            }
            final @Nullable RailEdge edge = leg.edge();
            if (edge != null) {
                out.computeIfAbsent(edge.from(), _ -> new ArrayList<>()).add(edge);
                out.computeIfAbsent(edge.to(), _ -> new ArrayList<>()).add(Objects.requireNonNull(leg.reversed()));
            }
            return true;
        }

        Set<RailLeg> legs() {
            return Collections.unmodifiableSet(legs);
        }

        /** Returns the graph gathered, whose rails {@code places} finds; the builder is not used after. */
        RailGraph build(final Places places) {
            return new RailGraph(this, places);
        }
    }

    private final Set<RailPos> stations;
    private final Set<RailPos> junctions;
    private final Set<RailLeg> legs;
    private final Places places;
    private final Map<RailPos, List<RailEdge>> out;
    private @Nullable List<RailEdge> edges;
    private boolean current = true;

    public RailGraph(
            final Collection<RailPos> stations, final Collection<RailPos> junctions, final List<RailEdge> edges) {
        this(stations, junctions, legs(edges));
    }

    private RailGraph(
            final Collection<RailPos> stations, final Collection<RailPos> junctions, final Set<RailLeg> legs) {
        this(stations, junctions, legs, index(legs)::get);
    }

    private static Set<RailLeg> legs(final Collection<RailEdge> edges) {
        final Set<RailLeg> legs = Collections.newSetFromMap(new IdentityHashMap<>());
        edges.forEach(edge -> legs.add(RailLeg.edge(UUID.randomUUID(), edge)));
        return legs;
    }

    /** Returns the graph of {@code legs}, which the trace of a network found. */
    static RailGraph ofLegs(
            final Collection<RailPos> stations, final Collection<RailPos> junctions, final Collection<RailLeg> legs) {
        final Set<RailLeg> held = Collections.newSetFromMap(new IdentityHashMap<>());
        held.addAll(legs);
        return new RailGraph(stations, junctions, held);
    }

    /**
     * Builds the graph of {@code legs}, whose rails {@code places} finds; a place on a leg not among them is none.
     */
    RailGraph(
            final Collection<RailPos> stations,
            final Collection<RailPos> junctions,
            final Collection<RailLeg> legs,
            final Places places) {
        this(gathered(stations, junctions, legs), places);
    }

    private static Builder gathered(
            final Collection<RailPos> stations, final Collection<RailPos> junctions, final Collection<RailLeg> legs) {
        final Builder builder = new Builder();
        stations.forEach(builder::station);
        junctions.forEach(builder::junction);
        legs.forEach(builder::leg);
        return builder;
    }

    private RailGraph(final Builder builder, final Places places) {
        this.stations = Collections.unmodifiableSet(builder.stations);
        this.junctions = Collections.unmodifiableSet(builder.junctions);
        this.legs = builder.legs;
        this.out = builder.out;
        this.places = places;
    }

    /** Returns where each rail of {@code legs} lies on them. */
    static Map<RailPos, Place> index(final Collection<RailLeg> legs) {
        final Map<RailPos, Place> places = new HashMap<>();
        for (final RailLeg leg : legs) {
            final List<RailPos> rails = leg.rails();
            for (int index = 0; index < rails.size(); index++) {
                places.put(rails.get(index), new Place(leg, index));
            }
        }
        return places;
    }

    /** Builds the graph of the network {@code rails}, in which {@code stations} are the station rails. */
    public static RailGraph of(final Map<RailPos, RailShape> rails, final Collection<RailPos> stations) {
        final Set<RailPos> onTrack = Set.copyOf(stations);
        final RailTrace trace = new RailTrace(
                new RailView() {
                    @Override
                    public boolean readable(final RailPos pos) {
                        return true;
                    }

                    @Override
                    public @Nullable RailShape shape(final RailPos pos) {
                        return rails.get(pos);
                    }
                },
                new RailTrace.Known() {
                    @Override
                    public @Nullable RailShape station(final RailPos pos) {
                        return onTrack.contains(pos) ? rails.get(pos) : null;
                    }

                    @Override
                    public boolean settled(final RailPos pos) {
                        return false;
                    }

                    @Override
                    public @Nullable RailLeg same(final RailLeg leg) {
                        return null;
                    }
                });
        onTrack.forEach(trace::look);
        trace.step(Integer.MAX_VALUE, Long.MAX_VALUE);
        final Set<RailPos> found = new TreeSet<>(trace.nodes());
        found.removeAll(trace.junctions());
        return ofLegs(found, trace.junctions(), trace.traced());
    }

    public Set<RailPos> stations() {
        return stations;
    }

    public Set<RailPos> junctions() {
        return junctions;
    }

    /** Returns the edges between the nodes, each once, in the order of the node they are kept from. */
    public List<RailEdge> edges() {
        @Nullable List<RailEdge> known = edges;
        if (known == null) {
            known = legs.stream()
                    .map(RailLeg::edge)
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparing(RailEdge::from).thenComparing(RailEdge::first))
                    .toList();
            edges = known;
        }
        return known;
    }

    /** Returns every leg of the graph: its edges, its spurs and the track that meets a station rail from the side. */
    Set<RailLeg> legs() {
        return Collections.unmodifiableSet(legs);
    }

    /** Returns every rail of the graph's nodes and edges. */
    public Set<RailPos> rails() {
        final Set<RailPos> rails = new TreeSet<>(stations);
        rails.addAll(junctions);
        edges().forEach(edge -> rails.addAll(edge.rails()));
        return rails;
    }

    /** Whether this graph is still what its network is; a ride on a graph that is not plans again. */
    public boolean current() {
        return current;
    }

    /** Notes that the network of this graph changed or is gone. */
    void retire() {
        current = false;
    }

    /** Returns where {@code rail} lies on a leg of this graph, or null when it lies on none. */
    @Nullable
    Place place(final RailPos rail) {
        final @Nullable Place place = places.place(rail);
        return place != null && legs.contains(place.leg()) ? place : null;
    }

    /** Returns where {@code rail} lies on an edge of this graph, between two nodes, or null when it lies on none. */
    @Nullable
    Place edgeAt(final RailPos rail) {
        final @Nullable Place place = place(rail);
        return place != null && place.leg().kind() == RailLeg.Kind.EDGE ? place : null;
    }

    /** Returns whether {@code rail} is a node of this graph, a rail of one of its edges or a rail of a spur. */
    boolean onTrack(final RailPos rail) {
        if (stations.contains(rail) || junctions.contains(rail)) {
            return true;
        }
        final @Nullable Place place = place(rail);
        return place != null && place.leg().kind() != RailLeg.Kind.SIDE;
    }

    /** Returns the way back from {@code rail} on a spur to the node the spur leaves, or null off every spur. */
    @Nullable
    RailEdge spur(final RailPos rail) {
        final @Nullable Place place = place(rail);
        if (place == null || place.leg().kind() != RailLeg.Kind.SPUR) {
            return null;
        }
        return RailEdge.of(
                rail,
                place.leg().from(),
                place.leg().rails().subList(0, place.index()).reversed());
    }

    /** Returns the edges that leave {@code node}, each walked from it. */
    List<RailEdge> leaving(final RailPos node) {
        return Objects.requireNonNullElse(out.get(node), List.of());
    }
}
