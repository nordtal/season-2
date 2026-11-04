package eu.nordtal.season.smp.rail;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Follows the track of a world from node to node, a bounded number of rails per {@link #step}.
 * From each station rail or junction it follows every rail that leaves it to the next node or the track's end, as a
 * {@link RailLeg}. It waits for what it cannot read yet; where the view gave up, the track ends.
 */
final class RailTrace {

    /** The most rails one leg holds; a leg longer than that is a loop that changed while it was followed. */
    static final int LONGEST = 1 << 20;

    /** What the trace is told of the world it follows the track of. */
    interface Known {

        /** Returns the axis of the station rail at {@code pos}, or null when there is none. */
        @Nullable
        RailShape station(RailPos pos);

        /** Whether the trace stops at the node {@code pos} without reading it again, since nothing changed there. */
        boolean settled(RailPos pos);

        /** Returns the leg the world already holds over the same track as {@code leg}, or null when it holds none. */
        @Nullable
        RailLeg same(RailLeg leg);

        /**
         * Returns the rails from {@code at} on, away from {@code previous}, that are known not to have changed.
         * The trace takes them without reading them; null means it must read {@code at}.
         */
        default @Nullable Skip skip(final RailPos previous, final RailPos at) {
            return null;
        }
    }

    /**
     * Rails the trace takes as they are known.
     *
     * @param rails the rails in order, at least one
     * @param next  the rail or node right after them, or null when the track ends with them
     */
    record Skip(List<RailPos> rails, @Nullable RailPos next) {}

    /** One end of a leg: the node, and the rail after it. */
    record End(RailPos node, RailPos first) {}

    private sealed interface Task permits Look, Follow, Find {}

    /** Reads one rail: a node is followed out of along every rail, a rail between nodes is followed both ways. */
    private record Look(RailPos rail) implements Task {}

    /** Follows the track that leaves {@code node} through {@code first}, up to the next node or its end. */
    private static final class Follow implements Task {
        private final RailPos node;
        private final RailPos first;
        private final List<RailPos> rails = new ArrayList<>();
        private RailPos previous;
        private RailPos at;

        Follow(final RailPos node, final RailPos first) {
            this.node = node;
            this.first = first;
            this.previous = node;
            this.at = first;
        }
    }

    /** Walks from a rail between nodes away from {@code previous}, until it meets a node to follow the track from. */
    private static final class Find implements Task {
        private RailPos previous;
        private RailPos at;

        Find(final RailPos previous, final RailPos at) {
            this.previous = previous;
            this.at = at;
        }
    }

    /** The tasks that wait for one chunk, and a position of it to ask whether it can be read now. */
    private record Waiting(RailPos probe, List<Task> tasks) {}

    private final RailView view;
    private final Known known;
    private final Deque<Task> queue = new ArrayDeque<>();
    private final Map<Long, Waiting> waiting = new LinkedHashMap<>();
    private final Set<RailPos> wanted = new LinkedHashSet<>();
    private final Set<RailPos> looked = new RailSet();
    private final Set<RailPos> seen = new RailSet();
    private final Set<End> ends = new HashSet<>();
    private final Set<RailPos> nodes = new HashSet<>();
    private final Set<RailPos> junctions = new HashSet<>();
    private final Set<RailPos> unread = new HashSet<>();
    private final List<RailPos> missing = new ArrayList<>();
    private final List<RailLeg> traced = new ArrayList<>();
    private final Set<RailLeg> kept = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<Long, Set<RailPos>> givenUp = new HashMap<>();
    private int left;
    private long deadline;
    private int read;

    RailTrace(final RailView view, final Known known) {
        this.view = view;
        this.known = known;
    }

    /** Reads {@code rail} once the trace steps: as a node, or as track between nodes, or as no rail. */
    void look(final RailPos rail) {
        queue.add(new Look(rail));
    }

    /** Follows the track that leaves the node {@code node} through {@code first}. */
    void follow(final RailPos node, final RailPos first) {
        queue.add(new Follow(node, first));
    }

    /**
     * Reads at most {@code budget} rails, and stops early once {@code deadline} by {@link System#nanoTime()} passed.
     * Returns whether the trace is done, which it is only once nothing waits any more.
     */
    boolean step(final int budget, final long deadline) {
        this.left = budget;
        this.deadline = deadline;
        requeueReadable();
        while (!queue.isEmpty()) {
            if (!affords()) {
                return false;
            }
            switch (queue.poll()) {
                case Look look -> look(look);
                case Follow follow -> follow(follow);
                case Find find -> find(find);
            }
        }
        return waiting.isEmpty();
    }

    private boolean affords() {
        return left > 0 && System.nanoTime() < deadline;
    }

    private void spend() {
        left--;
        read++;
    }

    private void requeueReadable() {
        final var entries = waiting.values().iterator();
        while (entries.hasNext()) {
            final Waiting next = entries.next();
            if (view.readable(next.probe()) || view.givenUp(next.probe())) {
                entries.remove();
                queue.addAll(next.tasks());
            }
        }
    }

    private void park(final Task task, final List<RailPos> unreadable) {
        final RailPos probe = unreadable.getFirst();
        waiting.computeIfAbsent(chunk(probe), _ -> new Waiting(probe, new ArrayList<>()))
                .tasks()
                .add(task);
        wanted.addAll(unreadable);
    }

    private void gaveUp(final RailPos pos) {
        givenUp.computeIfAbsent(chunk(pos), _ -> new HashSet<>()).add(pos);
    }

    private static long chunk(final RailPos pos) {
        return ((long) (pos.z() >> 4) << 32) | ((pos.x() >> 4) & 0xFFFFFFFFL);
    }

    /** Reads the rail at {@code pos} with what it connects to, or returns null and parks {@code task} until it can. */
    private @Nullable List<RailPos> connections(final Task task, final RailPos pos, final RailShape shape) {
        final List<RailPos> unreadable = new ArrayList<>(0);
        final List<RailPos> lost = new ArrayList<>(0);
        final List<RailPos> connected = Connections.of(pos, shape, view, unreadable, lost);
        lost.forEach(this::gaveUp);
        if (!unreadable.isEmpty()) {
            park(task, unreadable);
            return null;
        }
        spend();
        return connected;
    }

    private void look(final Look look) {
        final RailPos rail = look.rail();
        if (looked.contains(rail) || seen.contains(rail)) {
            return;
        }
        if (!view.readable(rail)) {
            if (view.givenUp(rail)) {
                looked.add(rail);
                unread.add(rail);
                gaveUp(rail);
            } else {
                park(look, List.of(rail));
            }
            return;
        }
        final @Nullable RailShape shape = view.shape(rail);
        final @Nullable RailShape station = known.station(rail);
        if (shape == null) {
            spend();
            looked.add(rail);
            if (station != null) {
                missing.add(rail);
            }
            return;
        }
        final @Nullable List<RailPos> connected = connections(look, rail, shape);
        if (connected == null) {
            return;
        }
        looked.add(rail);
        if (station != null || connected.size() >= 3) {
            expand(rail, station == null, connected);
            return;
        }
        seen.add(rail);
        connected.forEach(next -> queue.add(new Find(rail, next)));
    }

    /** Notes the node {@code node} and follows every rail that leaves it. */
    private void expand(final RailPos node, final boolean junction, final List<RailPos> connected) {
        looked.add(node);
        nodes.add(node);
        if (junction) {
            junctions.add(node);
        }
        connected.forEach(next -> queue.add(new Follow(node, next)));
    }

    /** Reads the node at {@code pos} later, unless nothing changed there or it was read already. */
    private void visit(final RailPos pos) {
        if (!known.settled(pos) && !looked.contains(pos)) {
            queue.add(new Look(pos));
        }
    }

    private void follow(final Follow follow) {
        if (follow.rails.isEmpty() && follow.at.equals(follow.first) && ends.contains(end(follow))) {
            return;
        }
        while (true) {
            if (!affords()) {
                queue.addFirst(follow);
                return;
            }
            final RailPos at = follow.at;
            if (known.settled(at)) {
                finish(follow, at);
                return;
            }
            final @Nullable Skip skip = known.skip(follow.previous, at);
            if (skip != null) {
                if (!skipped(follow, skip)) {
                    return;
                }
                continue;
            }
            if (!view.readable(at)) {
                if (unreadable(follow, at)) {
                    finish(follow, null);
                }
                return;
            }
            final @Nullable RailShape shape = view.shape(at);
            if (shape == null) {
                // The rail was broken while the trace waited; a station rail that went is read to find it missing.
                if (known.station(at) != null) {
                    visit(at);
                }
                finish(follow, null);
                return;
            }
            if (known.station(at) != null) {
                finish(follow, at);
                return;
            }
            final @Nullable List<RailPos> connected = connections(follow, at, shape);
            if (connected == null || !goesOn(follow, at, connected)) {
                return;
            }
        }
    }

    /** Takes the rails of {@code skip} into {@code follow}, and returns whether the track goes on past them. */
    private boolean skipped(final Follow follow, final Skip skip) {
        spend();
        follow.rails.addAll(skip.rails());
        seen.addAll(skip.rails());
        final @Nullable RailPos next = skip.next();
        if (next == null || follow.rails.size() >= LONGEST) {
            finish(follow, null);
            return false;
        }
        follow.previous = skip.rails().getLast();
        follow.at = next;
        return true;
    }

    /** Takes the rail {@code at} into {@code follow}, and returns whether the track goes on past it. */
    private boolean goesOn(final Follow follow, final RailPos at, final List<RailPos> connected) {
        final List<RailPos> onward = onward(connected, follow.previous);
        if (onward.size() >= 2) {
            if (turnedAway(at, connected)) {
                finish(follow, null);
                return false;
            }
            finish(follow, at);
            if (!looked.contains(at)) {
                expand(at, true, connected);
            }
            return false;
        }
        follow.rails.add(at);
        seen.add(at);
        if (onward.isEmpty() || follow.rails.size() >= LONGEST) {
            finish(follow, null);
            return false;
        }
        follow.previous = at;
        follow.at = onward.getFirst();
        return true;
    }

    /** Parks {@code task} until {@code at} can be read, or notes that the view gave up on it, and returns which. */
    private boolean unreadable(final Task task, final RailPos at) {
        if (view.givenUp(at)) {
            gaveUp(at);
            return true;
        }
        park(task, List.of(at));
        return false;
    }

    /**
     * Whether the track led to {@code at} from a rail it no longer connects to, as when it changed between two ticks.
     * The track ends there, and the re-check that change queued reads it again.
     */
    private boolean turnedAway(final RailPos at, final List<RailPos> connected) {
        return connected.size() < 3 || (looked.contains(at) && !nodes.contains(at));
    }

    private static List<RailPos> onward(final List<RailPos> connected, final RailPos previous) {
        return connected.stream().filter(pos -> !pos.equals(previous)).toList();
    }

    private static End end(final Follow follow) {
        return new End(follow.node, follow.first);
    }

    private void find(final Find find) {
        while (true) {
            if (!affords()) {
                queue.addFirst(find);
                return;
            }
            final RailPos at = find.at;
            if (seen.contains(at)) {
                return;
            }
            if (known.settled(at)) {
                queue.add(new Follow(at, find.previous));
                return;
            }
            if (known.station(at) != null) {
                visit(at);
                return;
            }
            final @Nullable Skip skip = known.skip(find.previous, at);
            if (skip != null) {
                spend();
                seen.addAll(skip.rails());
                final @Nullable RailPos next = skip.next();
                if (next == null) {
                    return;
                }
                find.previous = skip.rails().getLast();
                find.at = next;
                continue;
            }
            if (!view.readable(at)) {
                unreadable(find, at);
                return;
            }
            final @Nullable RailShape shape = view.shape(at);
            if (shape == null) {
                return;
            }
            final @Nullable List<RailPos> connected = connections(find, at, shape);
            if (connected == null) {
                return;
            }
            final List<RailPos> onward = onward(connected, find.previous);
            if (onward.size() >= 2) {
                if (!turnedAway(at, connected) && !looked.contains(at)) {
                    expand(at, true, connected);
                }
                return;
            }
            seen.add(at);
            if (onward.isEmpty()) {
                return;
            }
            find.previous = at;
            find.at = onward.getFirst();
        }
    }

    /** Ends {@code follow} at the node {@code to}, or where the track ends when it is null, and keeps its leg. */
    private void finish(final Follow follow, final @Nullable RailPos to) {
        if (to == null && follow.rails.isEmpty()) {
            return;
        }
        final RailLeg leg = leg(follow.node, follow.rails, to);
        if (!ends.add(new End(leg.from(), leg.first()))) {
            return;
        }
        final @Nullable RailPos end = leg.to();
        if (end != null) {
            ends.add(new End(end, leg.last()));
            visit(end);
        }
        final @Nullable RailLeg same = known.same(leg);
        if (same != null) {
            kept.add(same);
        } else {
            traced.add(leg);
        }
    }

    /** Returns the leg from {@code node} over {@code rails} to {@code to}, kept from its lower node. */
    private RailLeg leg(final RailPos node, final List<RailPos> rails, final @Nullable RailPos to) {
        if (to == null) {
            final RailLeg.Kind kind = along(node, rails.getFirst()) ? RailLeg.Kind.SPUR : RailLeg.Kind.SIDE;
            return new RailLeg(node, rails, null, kind, UUID.randomUUID());
        }
        final RailPos first = rails.isEmpty() ? to : rails.getFirst();
        final RailPos last = rails.isEmpty() ? node : rails.getLast();
        final RailLeg.Kind kind = along(node, first) && along(to, last) ? RailLeg.Kind.EDGE : RailLeg.Kind.SIDE;
        final int ends = node.compareTo(to);
        final boolean keeps = ends != 0 ? ends < 0 : first.compareTo(last) < 0;
        return keeps
                ? new RailLeg(node, rails, to, kind, UUID.randomUUID())
                : new RailLeg(to, rails.reversed(), node, kind, UUID.randomUUID());
    }

    /** Whether track that leaves {@code node} through {@code next} runs along it, as it must at a station rail. */
    private boolean along(final RailPos node, final RailPos next) {
        final @Nullable RailShape axis = known.station(node);
        return axis == null || axis.leadsTowards(Heading.between(node, next));
    }

    /** Returns the legs that the world does not hold yet, in the order they were followed. */
    List<RailLeg> traced() {
        return List.copyOf(traced);
    }

    /** Returns the legs the world holds that the trace followed again and found the same. */
    Set<RailLeg> kept() {
        return Collections.unmodifiableSet(kept);
    }

    /** Returns every node the trace read, station rails and junctions. */
    Set<RailPos> nodes() {
        return Collections.unmodifiableSet(nodes);
    }

    /** Returns the junctions among {@link #nodes()}. */
    Set<RailPos> junctions() {
        return Collections.unmodifiableSet(junctions);
    }

    /** Returns every position the trace was asked to look at or met as a node, and read. */
    Set<RailPos> looked() {
        return Collections.unmodifiableSet(looked);
    }

    /** Returns the positions it looked at that lie where the view gave up, which it could not read. */
    Set<RailPos> unread() {
        return Collections.unmodifiableSet(unread);
    }

    /** Returns the station rails it looked at that hold no rail. */
    List<RailPos> missing() {
        return List.copyOf(missing);
    }

    /** Returns, by chunk key as {@code Chunk#getChunkKey} gives it, the positions where the view gave up. */
    Map<Long, Set<RailPos>> givenUp() {
        return Collections.unmodifiableMap(givenUp);
    }

    /** Returns how many rails the trace read so far. */
    int read() {
        return read;
    }

    /** Returns and forgets the positions the trace waits for, so they can be made readable. */
    Set<RailPos> takeWanted() {
        final Set<RailPos> taken = Set.copyOf(wanted);
        wanted.clear();
        return taken;
    }
}
