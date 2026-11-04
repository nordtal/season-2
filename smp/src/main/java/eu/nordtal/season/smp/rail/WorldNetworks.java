package eu.nordtal.season.smp.rail;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The station rails and rail networks of one world, held in memory and changed only on the main thread.
 * What it holds of the track is the truth: a change of a block re-checks only the legs near it, from the node on
 * each side of the change, and joins or splits the networks it touched. Only a restart reads every network again.
 */
public final class WorldNetworks {

    /** How far from a changed block a rail may change too, as vanilla turns the rails next to a changed one. */
    static final int REACH = 2;

    /** One network: its nodes, and the graph it had when it last changed. */
    public record Network(RailGraph graph, Set<RailPos> nodes) {}

    /**
     * What a re-check changed, for the store.
     *
     * @param removed   the stored edges that are gone
     * @param added     the edges to store
     * @param junctions the junctions to store
     * @param crossings the stored junctions that are gone
     */
    public record Change(
            List<RailLeg> removed,
            List<RailLeg> added,
            Set<RailPos> junctions,
            Set<RailPos> crossings,
            List<StationRow> renamed,
            List<StationRow> lost) {

        /** Whether the store has nothing to write. */
        boolean empty() {
            return removed.isEmpty()
                    && added.isEmpty()
                    && junctions.isEmpty()
                    && crossings.isEmpty()
                    && renamed.isEmpty()
                    && lost.isEmpty();
        }
    }

    /** A station rail or a junction, with the legs that leave or reach it. */
    static final class Node {
        boolean junction;
        final List<RailLeg> legs = new ArrayList<>(3);

        @Nullable
        Network network;

        Node(final boolean junction) {
            this.junction = junction;
        }
    }

    final Map<RailPos, StationRow> stations = new HashMap<>();
    private final Map<UUID, StationRow> byId = new HashMap<>();
    Map<RailPos, Node> nodes = new HashMap<>();
    Map<RailTrace.End, RailLeg> legs = new HashMap<>();
    final RailMap<RailGraph.Place> places = new RailMap<>();
    final Set<Network> networks = Collections.newSetFromMap(new IdentityHashMap<>());
    final Deque<RailLeg> stale = new ArrayDeque<>();
    private int staleAt;

    public Optional<StationRow> station(final RailPos pos) {
        return Optional.ofNullable(stations.get(pos));
    }

    /** Returns the station with {@code id}, while it exists. */
    public Optional<StationRow> station(final UUID id) {
        return Optional.ofNullable(byId.get(id));
    }

    public Collection<StationRow> stations() {
        return Collections.unmodifiableCollection(stations.values());
    }

    public void addStation(final StationRow station) {
        final @Nullable StationRow old = stations.put(station.pos(), station);
        if (old != null) {
            byId.remove(old.id());
        }
        byId.put(station.id(), station);
    }

    public Optional<StationRow> removeStation(final RailPos pos) {
        final @Nullable StationRow row = stations.remove(pos);
        if (row != null) {
            byId.remove(row.id());
        }
        return Optional.ofNullable(row);
    }

    /** Returns the network {@code rail} belongs to: as one of its nodes, or as a rail of one of its legs. */
    public Optional<Network> networkOf(final RailPos rail) {
        final @Nullable Node node = nodes.get(rail);
        if (node != null) {
            return Optional.ofNullable(node.network);
        }
        final RailGraph.@Nullable Place place = place(rail);
        if (place == null) {
            return Optional.empty();
        }
        final @Nullable Node from = nodes.get(place.leg().from());
        return Optional.ofNullable(from == null ? null : from.network);
    }

    public Set<Network> networks() {
        return Collections.unmodifiableSet(networks);
    }

    /** Returns where {@code rail} lies on a leg this world holds now, or null when it lies on none. */
    RailGraph.@Nullable Place place(final RailPos rail) {
        final RailGraph.@Nullable Place place = places.get(rail);
        return place != null && holds(place.leg()) ? place : null;
    }

    private boolean holds(final RailLeg leg) {
        return legs.get(new RailTrace.End(leg.from(), leg.first())) == leg;
    }

    /** Returns the stations at {@code pos} and next to it, diagonals and one block up or down included. */
    public List<StationRow> stationsNear(final RailPos pos) {
        final List<StationRow> found = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    final @Nullable StationRow station =
                            stations.get(new RailPos(pos.x() + dx, pos.y() + dy, pos.z() + dz));
                    if (station != null) {
                        found.add(station);
                    }
                }
            }
        }
        return found;
    }

    /**
     * Returns the re-check that changes of the blocks at {@code changed} need, or null when they touch nothing.
     * They touch nothing when no leg, no node and no station rail of this world is within {@link #REACH} of them.
     */
    @Nullable
    RailRecheck recheck(final Collection<RailPos> changed, final RailView view, final Clock clock) {
        final Set<RailPos> region = new HashSet<>();
        for (final RailPos pos : changed) {
            for (int dx = -REACH; dx <= REACH; dx++) {
                for (int dy = -REACH; dy <= REACH; dy++) {
                    for (int dz = -REACH; dz <= REACH; dz++) {
                        region.add(new RailPos(pos.x() + dx, pos.y() + dy, pos.z() + dz));
                    }
                }
            }
        }
        final Set<RailLeg> candidates = Collections.newSetFromMap(new IdentityHashMap<>());
        final Set<RailPos> old = new TreeSet<>();
        final Set<RailPos> placed = new TreeSet<>();
        for (final RailPos pos : region) {
            final RailGraph.@Nullable Place place = place(pos);
            if (place != null) {
                candidates.add(place.leg());
            }
            final @Nullable Node node = nodes.get(pos);
            if (node != null) {
                old.add(pos);
                candidates.addAll(node.legs);
            }
            if (stations.containsKey(pos)) {
                placed.add(pos);
            }
        }
        if (candidates.isEmpty() && old.isEmpty() && placed.isEmpty()) {
            return null;
        }
        final RailRecheck job = new RailRecheck(this, view, clock, region, false, candidates, old);
        for (final RailLeg leg : candidates) {
            if (!region.contains(leg.from())) {
                job.trace().follow(leg.from(), leg.first());
            }
            final @Nullable RailPos to = leg.to();
            if (to != null && !region.contains(to)) {
                job.trace().follow(to, leg.last());
            }
        }
        old.forEach(job.trace()::look);
        placed.forEach(job.trace()::look);
        return job;
    }

    /** Returns the re-check that reads every network again from its station rails, as after a restart. */
    RailRecheck retrace(final RailView view, final Clock clock) {
        final Set<RailLeg> candidates = Collections.newSetFromMap(new IdentityHashMap<>());
        candidates.addAll(legs.values());
        final RailRecheck job =
                new RailRecheck(this, view, clock, Set.of(), true, candidates, Set.copyOf(nodes.keySet()));
        new TreeSet<>(stations.keySet()).forEach(job.trace()::look);
        return job;
    }

    /** Drops at most {@code budget} rails of gone legs from the index of where each rail lies. */
    void tidy(final int budget) {
        int left = budget;
        while (left > 0 && !stale.isEmpty()) {
            final RailLeg leg = stale.peek();
            final List<RailPos> rails = leg.rails();
            while (left > 0 && staleAt < rails.size()) {
                final RailPos rail = rails.get(staleAt++);
                final RailGraph.@Nullable Place place = places.get(rail);
                if (place != null && place.leg() == leg) {
                    places.remove(rail);
                }
                left--;
            }
            if (staleAt >= rails.size()) {
                stale.poll();
                staleAt = 0;
            }
        }
    }

    /** Returns the node at {@code pos}, which must be one. */
    Node node(final RailPos pos) {
        return Objects.requireNonNull(nodes.get(pos), "no node at " + pos);
    }

    private void link(final RailLeg leg) {
        legs.put(new RailTrace.End(leg.from(), leg.first()), leg);
        node(leg.from()).legs.add(leg);
        final @Nullable RailPos to = leg.to();
        if (to != null) {
            legs.put(new RailTrace.End(to, leg.last()), leg);
            final Node end = node(to);
            if (end != nodes.get(leg.from())) {
                end.legs.add(leg);
            }
        }
    }

    void unlinkLegOnly(final RailLeg leg) {
        legs.remove(new RailTrace.End(leg.from(), leg.first()), leg);
        final @Nullable RailPos to = leg.to();
        if (to != null) {
            legs.remove(new RailTrace.End(to, leg.last()), leg);
        }
    }

    /**
     * Returns the networks of a world as the store holds them, built from its stations, junctions and edges.
     * Built off the main thread, and handed to it whole.
     */
    static WorldNetworks restored(
            final List<StationRow> rows, final Set<RailPos> junctions, final Map<UUID, RailEdge> edges) {
        final WorldNetworks world = new WorldNetworks();
        rows.forEach(world::addStation);
        world.stations.keySet().forEach(pos -> world.nodes.put(pos, new Node(false)));
        junctions.forEach(pos -> world.nodes.putIfAbsent(pos, new Node(true)));
        edges.forEach((id, edge) -> {
            final RailLeg leg = RailLeg.edge(id, edge);
            if (world.legs.containsKey(new RailTrace.End(leg.from(), leg.first()))
                    || world.legs.containsKey(new RailTrace.End(Objects.requireNonNull(leg.to()), leg.last()))) {
                return;
            }
            world.nodes.putIfAbsent(leg.from(), new Node(false));
            world.nodes.putIfAbsent(edge.to(), new Node(false));
            world.link(leg);
            RailGraph.index(List.of(leg)).forEach(world.places::put);
        });
        final Set<RailPos> joined = new HashSet<>();
        for (final RailPos seed : new TreeSet<>(world.nodes.keySet())) {
            if (joined.add(seed)) {
                world.gather(seed, joined);
            }
        }
        return world;
    }

    /** Builds the network {@code seed} is on, as the store held it. */
    private void gather(final RailPos seed, final Set<RailPos> joined) {
        final Set<RailPos> members = new LinkedHashSet<>();
        final Set<RailLeg> track = Collections.newSetFromMap(new IdentityHashMap<>());
        final Deque<RailPos> open = new ArrayDeque<>(List.of(seed));
        while (!open.isEmpty()) {
            final RailPos pos = open.poll();
            members.add(pos);
            for (final RailLeg leg : node(pos).legs) {
                if (track.add(leg)) {
                    for (final @Nullable RailPos end : Arrays.asList(leg.from(), leg.to())) {
                        if (end != null && joined.add(end)) {
                            open.add(end);
                        }
                    }
                }
            }
        }
        final Set<RailPos> onTrack = new TreeSet<>();
        final Set<RailPos> crossings = new TreeSet<>();
        members.forEach(pos -> {
            if (stations.containsKey(pos)) {
                onTrack.add(pos);
            } else if (node(pos).junction) {
                crossings.add(pos);
            }
        });
        final Network network = new Network(new RailGraph(onTrack, crossings, track, this::place), members);
        members.forEach(pos -> node(pos).network = network);
        networks.add(network);
    }

    /** Takes over the stations placed in {@code before}, the world as it was until the store answered. */
    void takeOver(final WorldNetworks before) {
        before.stations().forEach(this::addStation);
    }

    List<StationRow> settleNames(final Network network, final Clock clock) {
        final List<StationNames.Named> named = new ArrayList<>();
        for (final RailPos pos : network.graph().stations()) {
            final @Nullable StationRow row = stations.get(pos);
            if (row != null && row.name() != null && row.named() != null) {
                named.add(new StationNames.Named(row.id(), row.name(), row.named()));
            }
        }
        if (named.size() < 2) {
            return List.of();
        }
        final Map<UUID, String> names = StationNames.settleClashes(named);
        final List<StationRow> renamed = new ArrayList<>();
        for (final RailPos pos : network.graph().stations()) {
            final @Nullable StationRow row = stations.get(pos);
            final @Nullable String name = row == null ? null : names.get(row.id());
            if (row != null && name != null) {
                final StationRow next = row.renamed(name, clock.instant());
                addStation(next);
                renamed.add(next);
            }
        }
        return renamed;
    }

    /** Returns every leg this world holds, for tests. */
    Set<RailLeg> legs() {
        final Set<RailLeg> held = Collections.newSetFromMap(new IdentityHashMap<>());
        held.addAll(legs.values());
        return held;
    }

    /** Returns how many rails the index of where each rail lies holds, gone legs not tidied away yet included. */
    int indexed() {
        return places.size();
    }

    /** Returns the stations by id, for a lookup by its key. */
    Map<UUID, StationRow> byId() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(byId));
    }
}
