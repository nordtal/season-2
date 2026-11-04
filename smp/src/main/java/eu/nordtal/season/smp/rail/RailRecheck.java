package eu.nordtal.season.smp.rail;

import eu.nordtal.season.smp.rail.WorldNetworks.Change;
import eu.nordtal.season.smp.rail.WorldNetworks.Network;
import eu.nordtal.season.smp.rail.WorldNetworks.Node;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * One re-check of the track of a world, run a little at a time until {@link #step} says it is done.
 * It reads the track, then works out the new legs, nodes and networks beside what the world holds, and changes the
 * world only in its last step, all at once, so a ride never sees a world half changed.
 */
final class RailRecheck implements RailTrace.Known {

    /** A share of a tick's work: so many units, until a time by {@link System#nanoTime()}. */
    private static final class Budget {
        private int left;
        private final long deadline;

        Budget(final int units, final long deadline) {
            this.left = units;
            this.deadline = deadline;
        }

        /** Takes one unit, and returns false once none is left or the time ran out. */
        boolean take() {
            return left-- > 0 && ((left & 63) != 0 || System.nanoTime() < deadline);
        }
    }

    /** A pass over some items, done a few at a time. */
    private static final class Pass<T> {
        private final Supplier<? extends Iterable<T>> items;
        private final Consumer<T> each;
        private @Nullable Iterator<T> at;

        Pass(final Supplier<? extends Iterable<T>> items, final Consumer<T> each) {
            this.items = items;
            this.each = each;
        }

        /** Runs on with the pass, and returns whether it is done. */
        boolean run(final Budget budget) {
            @Nullable Iterator<T> next = at;
            if (next == null) {
                next = items.get().iterator();
                at = next;
            }
            while (next.hasNext()) {
                if (!budget.take()) {
                    return false;
                }
                each.accept(next.next());
            }
            return true;
        }
    }

    private enum Phase {
        TRACE,
        INDEX,
        PLAN,
        FLOOD,
        SETTLE,
        COMMIT,
        DONE
    }

    /** A network found while the networks are gathered again. */
    private static final class Found {
        private final Set<RailPos> members = new LinkedHashSet<>();
        private final List<Node> nodes = new ArrayList<>();
        private final Deque<RailPos> open = new ArrayDeque<>();
        private final RailGraph.Builder graph = new RailGraph.Builder();
    }

    private final WorldNetworks world;
    private final Clock clock;
    private final Set<RailPos> region;
    private final boolean whole;
    private final RailTrace trace;
    private Phase phase = Phase.TRACE;
    private List<RailLeg> traced = List.of();
    private int indexLeg;
    private int indexRail;
    private final List<RailGraph.Place> deferred = new ArrayList<>();
    private @Nullable Change change;

    // What the re-check found, beside what the world holds until the commit.
    private Set<RailLeg> kept = Set.of();
    private Set<RailPos> found = Set.of();
    private Set<RailPos> missing = Set.of();
    private final Set<RailLeg> removed = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<RailLeg> gone = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<RailPos, Node> drafts = new HashMap<>();
    private final Set<RailPos> dropped = new HashSet<>();
    private final Set<Network> touched = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<RailPos, Boolean> junctionBefore = new HashMap<>();
    private final Set<RailPos> fresh = new LinkedHashSet<>();
    private final List<Pass<?>> plan;
    private int planned;

    // The networks gathered again.
    private final Set<RailPos> joined = new HashSet<>();
    private final List<Iterable<RailPos>> seeds = new ArrayList<>();
    private int seedSource;
    private @Nullable Iterator<RailPos> seedAt;
    private @Nullable Found gathering;
    private final List<Found> networksFound = new ArrayList<>();
    private final Set<RailLeg> stranded = Collections.newSetFromMap(new IdentityHashMap<>());

    // A whole re-check gathers the nodes and legs anew, and the commit puts them in place of the world's.
    private final Map<RailPos, Node> nextNodes = new HashMap<>();
    private final Map<RailTrace.End, RailLeg> nextLegs = new HashMap<>();

    // What the store is told, worked out before the commit.
    private final Set<RailLeg> added = Collections.newSetFromMap(new IdentityHashMap<>());
    private final List<RailLeg> removedEdges = new ArrayList<>();
    private final List<RailLeg> addedEdges = new ArrayList<>();
    private final Set<RailPos> junctionsAdded = new TreeSet<>();
    private final Set<RailPos> crossings = new TreeSet<>();
    private final List<Pass<?>> settle;
    private int settled;

    RailRecheck(
            final WorldNetworks world,
            final RailView view,
            final Clock clock,
            final Set<RailPos> region,
            final boolean whole,
            final Set<RailLeg> candidates,
            final Set<RailPos> old) {
        this.world = world;
        this.clock = clock;
        this.region = region;
        this.whole = whole;
        this.trace = new RailTrace(view, this);
        this.plan = List.of(
                new Pass<>(() -> candidates, this::unlessKept),
                new Pass<>(() -> traced, this::replacing),
                new Pass<>(() -> List.copyOf(removed), this::removing),
                new Pass<>(() -> old, this::droppingUnlessFound),
                new Pass<>(() -> found, this::finding),
                new Pass<>(() -> traced, this::linking));
        this.settle = List.of(
                new Pass<>(() -> traced, this::adding),
                new Pass<>(() -> gone, leg -> edge(leg, removedEdges)),
                new Pass<>(() -> added, leg -> edge(leg, addedEdges)),
                new Pass<>(junctionBefore::entrySet, this::junctionAfter));
    }

    RailTrace trace() {
        return trace;
    }

    /** Whether this re-check reads every network again. */
    boolean whole() {
        return whole;
    }

    /** Returns what the finished re-check changed. */
    Change change() {
        final @Nullable Change done = change;
        if (done == null) {
            throw new IllegalStateException("the re-check is not done");
        }
        return done;
    }

    @Override
    public @Nullable RailShape station(final RailPos pos) {
        final @Nullable StationRow row = world.stations.get(pos);
        return row == null ? null : RailShape.valueOf(row.axis());
    }

    @Override
    public boolean settled(final RailPos pos) {
        return !whole && world.nodes.containsKey(pos) && !region.contains(pos);
    }

    @Override
    public @Nullable RailLeg same(final RailLeg leg) {
        final @Nullable RailLeg held = world.legs.get(new RailTrace.End(leg.from(), leg.first()));
        return held != null && held.sameTrack(leg) ? held : null;
    }

    @Override
    public RailTrace.@Nullable Skip skip(final RailPos previous, final RailPos at) {
        if (whole || region.contains(at) || world.stations.containsKey(at)) {
            return null;
        }
        final RailGraph.@Nullable Place place = world.place(at);
        if (place == null) {
            return null;
        }
        final RailLeg leg = place.leg();
        final int index = place.index();
        final int direction;
        if (previous.equals(leg.at(index - 1))) {
            direction = 1;
        } else if (previous.equals(leg.at(index + 1))) {
            direction = -1;
        } else {
            return null;
        }
        final List<RailPos> rails = leg.rails();
        int end = index;
        while (end >= 0
                && end < rails.size()
                && !region.contains(rails.get(end))
                && !world.stations.containsKey(rails.get(end))) {
            end += direction;
        }
        final List<RailPos> run = direction > 0
                ? rails.subList(index, end)
                : rails.subList(end + 1, index + 1).reversed();
        return new RailTrace.Skip(run, leg.at(end));
    }

    /**
     * Does at most {@code budget} rails of work, and returns whether the re-check is done and applied.
     * It stops early once {@code deadline} by {@link System#nanoTime()} passed.
     */
    boolean step(final int budget, final long deadline) {
        while (phase != Phase.DONE && System.nanoTime() < deadline) {
            switch (phase) {
                case TRACE -> {
                    if (!trace.step(budget, deadline)) {
                        return false;
                    }
                    traced = trace.traced();
                    kept = trace.kept();
                    found = trace.nodes();
                    missing = new HashSet<>(trace.missing());
                    phase = Phase.INDEX;
                }
                case INDEX -> {
                    if (!index(budget * 16, deadline)) {
                        return false;
                    }
                    phase = Phase.PLAN;
                }
                case PLAN -> {
                    final Budget share = new Budget(budget * 4, deadline);
                    while (planned < plan.size()) {
                        if (!plan.get(planned).run(share)) {
                            return false;
                        }
                        planned++;
                    }
                    seeds.add(fresh);
                    touched.forEach(network -> seeds.add(network.nodes()));
                    phase = Phase.FLOOD;
                }
                case FLOOD -> {
                    if (!flood(new Budget(budget * 4, deadline))) {
                        return false;
                    }
                    phase = Phase.SETTLE;
                }
                case SETTLE -> {
                    final Budget share = new Budget(budget * 4, deadline);
                    while (settled < settle.size()) {
                        if (!settle.get(settled).run(share)) {
                            return false;
                        }
                        settled++;
                    }
                    phase = Phase.COMMIT;
                }
                case COMMIT -> {
                    change = commit();
                    phase = Phase.DONE;
                }
                case DONE -> {}
            }
        }
        return phase == Phase.DONE;
    }

    /** Notes where the rails of the new legs lie; a rail on a leg that is held now waits for the commit. */
    private boolean index(final int budget, final long deadline) {
        int left = budget;
        while (indexLeg < traced.size()) {
            final RailLeg leg = traced.get(indexLeg);
            final List<RailPos> rails = leg.rails();
            while (indexRail < rails.size()) {
                if (left-- <= 0 || ((left & 1023) == 0 && System.nanoTime() >= deadline)) {
                    return false;
                }
                final RailPos rail = rails.get(indexRail);
                final RailGraph.Place at = new RailGraph.Place(leg, indexRail);
                if (world.place(rail) == null) {
                    world.places.put(rail, at);
                } else {
                    deferred.add(at);
                }
                indexRail++;
            }
            indexLeg++;
            indexRail = 0;
        }
        return true;
    }

    // The plan: each pass below looks at one item, and changes only the drafts beside the world.

    private void unlessKept(final RailLeg leg) {
        if (!kept.contains(leg)) {
            removed.add(leg);
        }
    }

    private void replacing(final RailLeg leg) {
        replaced(new RailTrace.End(leg.from(), leg.first()));
        final @Nullable RailPos to = leg.to();
        if (to != null) {
            replaced(new RailTrace.End(to, leg.last()));
        }
    }

    private void replaced(final RailTrace.End end) {
        final @Nullable RailLeg held = world.legs.get(end);
        if (held != null && !kept.contains(held)) {
            removed.add(held);
        }
    }

    private void removing(final RailLeg leg) {
        touch(leg.from());
        draft(leg.from()).legs.remove(leg);
        final @Nullable RailPos to = leg.to();
        if (to != null) {
            touch(to);
            draft(to).legs.remove(leg);
        }
        gone.add(leg);
    }

    private void droppingUnlessFound(final RailPos pos) {
        if (found.contains(pos)) {
            return;
        }
        touch(pos);
        final @Nullable Node node = world.nodes.get(pos);
        drafts.remove(pos);
        dropped.add(pos);
        if (node != null) {
            for (final RailLeg leg : node.legs) {
                removed.add(leg);
                gone.add(leg);
                for (final @Nullable RailPos end : Arrays.asList(leg.from(), leg.to())) {
                    if (end != null && !end.equals(pos) && exists(end)) {
                        draft(end).legs.remove(leg);
                    }
                }
            }
        }
    }

    private void finding(final RailPos pos) {
        touch(pos);
        final boolean junction = trace.junctions().contains(pos);
        if (!exists(pos)) {
            drafts.put(pos, new Node(junction));
        } else if (view(pos).junction != junction) {
            draft(pos).junction = junction;
        }
        fresh.add(pos);
    }

    private void linking(final RailLeg leg) {
        touch(leg.from());
        draft(leg.from()).legs.add(leg);
        final @Nullable RailPos to = leg.to();
        if (to != null) {
            touch(to);
            if (!to.equals(leg.from())) {
                draft(to).legs.add(leg);
            }
        }
        fresh.add(leg.from());
    }

    /** Notes the network {@code pos} was on, and whether it was a junction, before this re-check. */
    private void touch(final RailPos pos) {
        final @Nullable Node node = world.nodes.get(pos);
        junctionBefore.putIfAbsent(pos, node != null && node.junction);
        if (node != null && node.network != null) {
            touched.add(node.network);
        }
    }

    /** Whether {@code pos} is a node once this re-check is applied. */
    private boolean exists(final RailPos pos) {
        return drafts.containsKey(pos) || (!dropped.contains(pos) && world.nodes.containsKey(pos));
    }

    /** Returns the node at {@code pos} as it is once this re-check is applied. */
    private Node view(final RailPos pos) {
        final @Nullable Node draft = drafts.get(pos);
        return draft != null ? draft : world.node(pos);
    }

    /** Returns the draft of the node at {@code pos}, a copy of the node the world holds there until the commit. */
    private Node draft(final RailPos pos) {
        return drafts.computeIfAbsent(pos, _ -> {
            final Node held = world.node(pos);
            final Node copy = new Node(held.junction);
            copy.legs.addAll(held.legs);
            return copy;
        });
    }

    private boolean isStation(final RailPos pos) {
        return world.stations.containsKey(pos) && !missing.contains(pos);
    }

    /** Gathers the networks on the nodes this re-check touched, a node or a leg per unit of the budget. */
    private boolean flood(final Budget budget) {
        while (true) {
            @Nullable Found network = gathering;
            if (network == null) {
                final @Nullable RailPos seed = nextSeed(budget);
                if (seed == null) {
                    return seedSource >= seeds.size();
                }
                network = new Found();
                network.open.add(seed);
                gathering = network;
            }
            while (!network.open.isEmpty()) {
                if (!budget.take()) {
                    return false;
                }
                final RailPos pos = network.open.poll();
                final Node node = gathered(pos);
                network.members.add(pos);
                network.nodes.add(node);
                if (isStation(pos)) {
                    network.graph.station(pos);
                } else if (node.junction) {
                    network.graph.junction(pos);
                }
                for (final RailLeg leg : node.legs) {
                    if (network.graph.leg(leg)) {
                        if (whole) {
                            nextLegs.put(new RailTrace.End(leg.from(), leg.first()), leg);
                            final @Nullable RailPos end = leg.to();
                            if (end != null) {
                                nextLegs.put(new RailTrace.End(end, leg.last()), leg);
                            }
                        }
                        reach(leg.from(), network);
                        final @Nullable RailPos to = leg.to();
                        if (to != null) {
                            reach(to, network);
                        }
                    }
                }
            }
            gathering = null;
            if (network.graph.hasStation()) {
                networksFound.add(network);
            } else {
                strand(network);
            }
        }
    }

    /** Drops the nodes and legs of {@code network}, which has no station rail and so is no network. */
    private void strand(final Found network) {
        for (final RailPos pos : network.members) {
            junctionBefore.putIfAbsent(pos, view(pos).junction);
            drafts.remove(pos);
            dropped.add(pos);
            nextNodes.remove(pos);
        }
        for (final RailLeg leg : network.graph.legs()) {
            nextLegs.remove(new RailTrace.End(leg.from(), leg.first()), leg);
            final @Nullable RailPos end = leg.to();
            if (end != null) {
                nextLegs.remove(new RailTrace.End(end, leg.last()), leg);
            }
        }
        stranded.addAll(network.graph.legs());
        gone.addAll(network.graph.legs());
    }

    /**
     * Returns the node at {@code pos} as the commit leaves it.
     * In a whole re-check that is a node of its own, which the commit puts in place of the world's; else it is the
     * draft or the node the world holds.
     */
    private Node gathered(final RailPos pos) {
        final Node node = view(pos);
        if (!whole) {
            return node;
        }
        final Node fresh = new Node(node.junction);
        fresh.legs.addAll(node.legs);
        nextNodes.put(pos, fresh);
        return fresh;
    }

    private void adding(final RailLeg leg) {
        if (!gone.remove(leg)) {
            added.add(leg);
        }
    }

    private static void edge(final RailLeg leg, final List<RailLeg> edges) {
        if (leg.kind() == RailLeg.Kind.EDGE) {
            edges.add(leg);
        }
    }

    private void junctionAfter(final Map.Entry<RailPos, Boolean> before) {
        final RailPos pos = before.getKey();
        final boolean after = exists(pos) && view(pos).junction;
        if (after && !before.getValue()) {
            junctionsAdded.add(pos);
        } else if (before.getValue() && !after) {
            crossings.add(pos);
        }
    }

    /** Returns the next seed not gathered yet, or null when none is left or the budget ran out first. */
    private @Nullable RailPos nextSeed(final Budget budget) {
        while (seedSource < seeds.size()) {
            @Nullable Iterator<RailPos> at = seedAt;
            if (at == null) {
                at = seeds.get(seedSource).iterator();
                seedAt = at;
            }
            while (at.hasNext()) {
                if (!budget.take()) {
                    return null;
                }
                final RailPos seed = at.next();
                if (exists(seed) && joined.add(seed)) {
                    return seed;
                }
            }
            seedAt = null;
            seedSource++;
        }
        return null;
    }

    private void reach(final RailPos pos, final Found network) {
        if (exists(pos) && joined.add(pos)) {
            network.open.add(pos);
        }
    }

    /** Changes the world to what the re-check found, all at once. */
    private Change commit() {
        if (whole) {
            world.nodes = nextNodes;
            world.legs = nextLegs;
        } else {
            removed.forEach(world::unlinkLegOnly);
            dropped.forEach(world.nodes::remove);
            world.nodes.putAll(drafts);
            for (final RailLeg leg : traced) {
                world.legs.put(new RailTrace.End(leg.from(), leg.first()), leg);
                final @Nullable RailPos to = leg.to();
                if (to != null) {
                    world.legs.put(new RailTrace.End(to, leg.last()), leg);
                }
            }
            stranded.forEach(world::unlinkLegOnly);
        }
        for (final RailGraph.Place place : deferred) {
            world.places.put(place.leg().rails().get(place.index()), place);
        }
        final List<StationRow> lost = new ArrayList<>();
        for (final RailPos pos : missing) {
            world.removeStation(pos).ifPresent(lost::add);
        }
        for (final Network network : touched) {
            network.graph().retire();
            world.networks.remove(network);
        }
        final List<StationRow> renamed = new ArrayList<>();
        for (final Found built : networksFound) {
            final Network network =
                    new Network(built.graph.build(world::place), Collections.unmodifiableSet(built.members));
            for (final Node node : built.nodes) {
                node.network = network;
            }
            world.networks.add(network);
            renamed.addAll(world.settleNames(network, clock));
        }
        world.stale.addAll(gone);
        return new Change(
                List.copyOf(removedEdges),
                List.copyOf(addedEdges),
                junctionsAdded,
                crossings,
                List.copyOf(renamed),
                List.copyOf(lost));
    }
}
