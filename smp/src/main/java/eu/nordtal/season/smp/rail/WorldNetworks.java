package eu.nordtal.season.smp.rail;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** The station rails and rail networks of one world, held in memory and changed only on the main thread. */
public final class WorldNetworks {

    /** One network: its graph, and every rail the walk found for it, the track that ends nowhere included. */
    public record Network(RailGraph graph, Set<RailPos> rails) {}

    /** What a walk replaces: the networks it covers and the station rails it starts from. */
    public record Walk(Set<Network> replaced, Set<RailPos> starts) {}

    /** What a finished walk changed, for the store. */
    public record Replacement(
            Set<Network> removed, List<Network> added, List<StationRow> renamed, List<StationRow> lost) {}

    private final Map<RailPos, StationRow> stations = new HashMap<>();
    private final Set<Network> networks = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<RailPos, Network> byRail = new HashMap<>();

    public Optional<StationRow> station(final RailPos pos) {
        return Optional.ofNullable(stations.get(pos));
    }

    public Collection<StationRow> stations() {
        return Collections.unmodifiableCollection(stations.values());
    }

    public void addStation(final StationRow station) {
        stations.put(station.pos(), station);
    }

    public Optional<StationRow> removeStation(final RailPos pos) {
        return Optional.ofNullable(stations.remove(pos));
    }

    public Optional<Network> networkOf(final RailPos rail) {
        return Optional.ofNullable(byRail.get(rail));
    }

    public Set<Network> networks() {
        return Collections.unmodifiableSet(networks);
    }

    /** Whether {@code pos} or a position next to it, diagonals and one block up or down included, is tracked. */
    public boolean tracksNear(final RailPos pos) {
        return !near(pos).isEmpty();
    }

    private Set<Network> near(final RailPos pos) {
        final Set<Network> found = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    final @Nullable Network network = byRail.get(new RailPos(pos.x() + dx, pos.y() + dy, pos.z() + dz));
                    if (network != null) {
                        found.add(network);
                    }
                }
            }
        }
        return found;
    }

    /**
     * Returns the walk that a change at {@code changed} needs: the networks near it, from all of their stations.
     * A change near no network and at no station needs none, so the walk has no starts.
     */
    public Walk walkFor(final Collection<RailPos> changed) {
        final Set<Network> replaced = Collections.newSetFromMap(new IdentityHashMap<>());
        final Set<RailPos> starts = new TreeSet<>();
        for (final RailPos pos : changed) {
            replaced.addAll(near(pos));
            if (stations.containsKey(pos)) {
                starts.add(pos);
            }
        }
        for (final Network network : replaced) {
            network.graph().stations().stream().filter(stations::containsKey).forEach(starts::add);
        }
        return new Walk(replaced, starts);
    }

    /** Replaces the networks a walk covered with the networks it found, and settles the names that now clash. */
    public Replacement replace(
            final Walk walk, final List<RailWalk.Found> found, final List<RailPos> missing, final Instant now) {
        final List<StationRow> lost = new ArrayList<>();
        for (final RailPos pos : missing) {
            removeStation(pos).ifPresent(lost::add);
        }
        final Set<Network> removed = Collections.newSetFromMap(new IdentityHashMap<>());
        removed.addAll(walk.replaced());
        for (final RailWalk.Found network : found) {
            network.rails().keySet().stream()
                    .map(byRail::get)
                    .filter(Objects::nonNull)
                    .forEach(removed::add);
        }
        removed.forEach(this::forget);
        final List<Network> added = new ArrayList<>();
        final List<StationRow> renamed = new ArrayList<>();
        for (final RailWalk.Found rails : found) {
            final List<RailPos> onTrack = rails.rails().keySet().stream()
                    .filter(stations::containsKey)
                    .toList();
            final Network network = new Network(
                    RailGraph.of(rails.rails(), onTrack),
                    Set.copyOf(rails.rails().keySet()));
            remember(network);
            added.add(network);
            renamed.addAll(settleNames(network, now));
        }
        return new Replacement(removed, added, renamed, lost);
    }

    /** Puts back what the store holds: the stations, and the networks built from the junctions and edges. */
    public void restore(final List<StationRow> rows, final Set<RailPos> junctions, final List<RailEdge> edges) {
        rows.forEach(this::addStation);
        final Map<RailPos, RailPos> parent = new HashMap<>();
        for (final RailEdge edge : edges) {
            union(parent, edge.from(), edge.to());
        }
        stations.keySet().forEach(pos -> find(parent, pos));
        junctions.forEach(pos -> find(parent, pos));
        final Map<RailPos, List<RailEdge>> edgesByRoot = new HashMap<>();
        edges.forEach(edge -> edgesByRoot
                .computeIfAbsent(find(parent, edge.from()), _ -> new ArrayList<>())
                .add(edge));
        final Map<RailPos, List<RailPos>> nodesByRoot = new HashMap<>();
        for (final RailPos node : new TreeSet<>(parent.keySet())) {
            nodesByRoot
                    .computeIfAbsent(find(parent, node), _ -> new ArrayList<>())
                    .add(node);
        }
        nodesByRoot.forEach((root, nodes) -> {
            final List<RailPos> onTrack =
                    nodes.stream().filter(stations::containsKey).toList();
            final List<RailPos> crossings =
                    nodes.stream().filter(junctions::contains).toList();
            final RailGraph graph = new RailGraph(onTrack, crossings, edgesByRoot.getOrDefault(root, List.of()));
            remember(new Network(graph, graph.rails()));
        });
    }

    private static RailPos find(final Map<RailPos, RailPos> parent, final RailPos pos) {
        RailPos root = parent.computeIfAbsent(pos, p -> p);
        while (!root.equals(parent.get(root))) {
            root = Objects.requireNonNull(parent.get(root));
        }
        parent.put(pos, root);
        return root;
    }

    private static void union(final Map<RailPos, RailPos> parent, final RailPos a, final RailPos b) {
        final RailPos one = find(parent, a);
        final RailPos other = find(parent, b);
        if (!one.equals(other)) {
            parent.put(one.compareTo(other) < 0 ? other : one, one.compareTo(other) < 0 ? one : other);
        }
    }

    private List<StationRow> settleNames(final Network network, final Instant now) {
        final List<StationNames.Named> named = new ArrayList<>();
        for (final RailPos pos : network.graph().stations()) {
            final @Nullable StationRow row = stations.get(pos);
            if (row != null && row.name() != null && row.named() != null) {
                named.add(new StationNames.Named(row.id(), row.name(), row.named()));
            }
        }
        final Map<UUID, String> names = StationNames.settleClashes(named);
        final List<StationRow> renamed = new ArrayList<>();
        for (final RailPos pos : network.graph().stations()) {
            final @Nullable StationRow row = stations.get(pos);
            final @Nullable String name = row == null ? null : names.get(row.id());
            if (row != null && name != null) {
                final StationRow next = row.renamed(name, now);
                stations.put(pos, next);
                renamed.add(next);
            }
        }
        return renamed;
    }

    private void remember(final Network network) {
        networks.add(network);
        network.rails().forEach(rail -> byRail.put(rail, network));
    }

    private void forget(final Network network) {
        networks.remove(network);
        network.rails().forEach(rail -> byRail.remove(rail, network));
    }
}
