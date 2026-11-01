package eu.nordtal.season.smp.rail;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * A flood fill along the rails from a list of start rails, which finds one network per group of connected starts.
 * It reads a bounded number of rails per {@link #step}, so a long track is walked over many ticks, and it waits
 * for positions it cannot read yet instead of loading them.
 */
public final class RailWalk {

    /** The rails of one network, and whether the walk stopped at its bound before it reached them all. */
    public record Found(Map<RailPos, RailShape> rails, boolean bounded) {}

    private final RailView view;
    private final int limit;
    private final Deque<RailPos> starts;
    private final Set<RailPos> seen = new HashSet<>();
    private final List<Found> found = new ArrayList<>();
    private final List<RailPos> missing = new ArrayList<>();
    private final Set<RailPos> wanted = new LinkedHashSet<>();

    private @Nullable Map<RailPos, RailShape> current;
    private boolean bounded;
    private final Deque<RailPos> frontier = new ArrayDeque<>();
    private final Set<RailPos> waiting = new LinkedHashSet<>();

    /** Walks from {@code starts}, at most {@code limit} rails per network. */
    public RailWalk(final RailView view, final Collection<RailPos> starts, final int limit) {
        this.view = view;
        this.limit = limit;
        this.starts = new ArrayDeque<>(new LinkedHashSet<>(starts));
    }

    /** Expands at most {@code budget} rails and returns whether the walk is done. */
    public boolean step(final int budget) {
        int left = budget;
        while (left > 0) {
            final Map<RailPos, RailShape> network = current;
            if (network == null) {
                if (!begin()) {
                    return starts.isEmpty();
                }
                continue;
            }
            if (frontier.isEmpty()) {
                retryWaiting();
            }
            final RailPos next = frontier.poll();
            if (next == null) {
                if (!waiting.isEmpty()) {
                    return false;
                }
                found.add(new Found(Map.copyOf(network), bounded));
                current = null;
                continue;
            }
            expand(network, next);
            left--;
        }
        return current == null && starts.isEmpty();
    }

    /** Starts the next network, and returns false when no start can be read now. */
    private boolean begin() {
        for (int tries = starts.size(); tries > 0; tries--) {
            final RailPos start = starts.poll();
            if (start == null || seen.contains(start)) {
                continue;
            }
            if (!view.readable(start)) {
                wanted.add(start);
                starts.addLast(start);
                continue;
            }
            final @Nullable RailShape shape = view.shape(start);
            if (shape == null) {
                missing.add(start);
                continue;
            }
            final Map<RailPos, RailShape> network = new HashMap<>();
            network.put(start, shape);
            seen.add(start);
            current = network;
            bounded = false;
            frontier.add(start);
            return true;
        }
        return false;
    }

    private void retryWaiting() {
        final Iterator<RailPos> rails = waiting.iterator();
        while (rails.hasNext()) {
            final RailPos rail = rails.next();
            if (allReadableAround(rail)) {
                rails.remove();
                frontier.addFirst(rail);
            }
        }
    }

    private boolean allReadableAround(final RailPos rail) {
        final @Nullable RailShape shape = view.shape(rail);
        if (shape == null) {
            // The rail was broken while the walk waited; the walk that its break starts covers it.
            return true;
        }
        final List<RailPos> unreadable = new ArrayList<>();
        Connections.of(rail, shape, view, unreadable);
        return unreadable.isEmpty();
    }

    private void expand(final Map<RailPos, RailShape> network, final RailPos rail) {
        final @Nullable RailShape shape = network.get(rail);
        if (shape == null) {
            return;
        }
        final List<RailPos> unreadable = new ArrayList<>();
        for (final RailPos next : Connections.of(rail, shape, view, unreadable)) {
            if (!seen.add(next)) {
                continue;
            }
            if (network.size() >= limit) {
                bounded = true;
                continue;
            }
            network.put(next, Objects.requireNonNull(view.shape(next), "a connected rail has a shape"));
            frontier.add(next);
        }
        if (!unreadable.isEmpty() && !bounded) {
            waiting.add(rail);
            wanted.addAll(unreadable);
        }
    }

    /** Returns the networks found so far, in the order of their first start. */
    public List<Found> found() {
        return List.copyOf(found);
    }

    /** Returns the starts that held no rail. */
    public List<RailPos> missing() {
        return List.copyOf(missing);
    }

    /** Returns and forgets the positions the walk waits for, so they can be made readable. */
    public Set<RailPos> takeWanted() {
        final Set<RailPos> taken = Set.copyOf(wanted);
        wanted.clear();
        return taken;
    }
}
