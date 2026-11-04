package eu.nordtal.season.smp.rail;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The track that leaves a node, a station rail or a junction, up to the next node or to where it ends.
 * Track between two nodes is kept from the lower one, so each piece of track is one leg whichever end it was
 * followed from. Two legs are equal only when they are the same object; {@link #sameTrack} compares their rails.
 */
final class RailLeg {

    /** What a cart may do on a leg. */
    enum Kind {
        /** Track between two nodes that a cart rides. */
        EDGE,
        /** Track that leaves a node and ends, which a cart rides only back to that node. */
        SPUR,
        /** Track that meets a station rail from its side: it joins the network, but no cart rides it. */
        SIDE
    }

    private final RailPos from;
    private final List<RailPos> rails;
    private final @Nullable RailPos to;
    private final Kind kind;
    private final UUID id;
    private @Nullable RailEdge edge;
    private @Nullable RailEdge reversed;

    /**
     * @param rails the rails after {@code from} in order, both nodes left out
     * @param to    the node the track reaches, or null when it ends
     * @param id    the id the store keeps an edge under
     */
    RailLeg(final RailPos from, final List<RailPos> rails, final @Nullable RailPos to, final Kind kind, final UUID id) {
        this.from = from;
        this.rails = List.copyOf(rails);
        this.to = to;
        this.kind = kind;
        this.id = id;
        if (to == null && this.rails.isEmpty()) {
            throw new IllegalArgumentException("a leg that ends holds at least one rail");
        }
    }

    /** Returns a stored edge as a leg. */
    static RailLeg edge(final UUID id, final RailEdge edge) {
        return new RailLeg(edge.from(), edge.rails(), edge.to(), Kind.EDGE, id);
    }

    RailPos from() {
        return from;
    }

    List<RailPos> rails() {
        return rails;
    }

    @Nullable
    RailPos to() {
        return to;
    }

    Kind kind() {
        return kind;
    }

    UUID id() {
        return id;
    }

    /** Returns the rail right after {@code from}. */
    RailPos first() {
        return rails.isEmpty() ? Objects.requireNonNull(to) : rails.getFirst();
    }

    /** Returns the rail right before {@code to}, or the last rail of a leg that ends. */
    RailPos last() {
        return rails.isEmpty() ? from : rails.getLast();
    }

    /** Returns the rail {@code index} steps from {@code from}: -1 is {@code from}, the size of the rails {@code to}. */
    @Nullable
    RailPos at(final int index) {
        if (index == -1) {
            return from;
        }
        return index == rails.size() ? to : rails.get(index);
    }

    /** Whether {@code other} runs over the same rails between the same nodes, and a cart may do the same on it. */
    boolean sameTrack(final RailLeg other) {
        return from.equals(other.from)
                && Objects.equals(to, other.to)
                && kind == other.kind
                && rails.equals(other.rails);
    }

    /** Returns this leg as the edge a route rides, or null when no cart rides it between two nodes. */
    @Nullable
    RailEdge edge() {
        if (kind != Kind.EDGE) {
            return null;
        }
        @Nullable RailEdge known = edge;
        if (known == null) {
            known = RailEdge.of(from, Objects.requireNonNull(to), rails);
            edge = known;
        }
        return known;
    }

    /** Returns this leg as the edge a route rides from {@code to}, or null when no cart rides it between two nodes. */
    @Nullable
    RailEdge reversed() {
        final @Nullable RailEdge forward = edge();
        if (forward == null) {
            return null;
        }
        @Nullable RailEdge known = reversed;
        if (known == null) {
            known = forward.reversed();
            reversed = known;
        }
        return known;
    }

    @Override
    public String toString() {
        return kind + " " + from + " -> " + to + " over " + rails.size() + " rail(s)";
    }
}
