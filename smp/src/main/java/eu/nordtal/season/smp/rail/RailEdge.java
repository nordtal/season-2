package eu.nordtal.season.smp.rail;

import java.util.List;

/**
 * The track between two nodes of a network, a station or a junction each.
 *
 * @param rails  the rails between the two nodes in order from {@code from}, both nodes left out
 * @param length the number of steps from one node to the other
 */
public record RailEdge(RailPos from, RailPos to, List<RailPos> rails, int length) {

    public RailEdge {
        rails = List.copyOf(rails);
    }

    public static RailEdge of(final RailPos from, final RailPos to, final List<RailPos> rails) {
        return new RailEdge(from, to, rails, rails.size() + 1);
    }

    /** Returns the same track walked from {@code to}. */
    public RailEdge reversed() {
        return new RailEdge(to, from, rails.reversed(), length);
    }

    /** Returns the rail right after {@code from}: the first rail between, or {@code to}. */
    public RailPos first() {
        return rails.isEmpty() ? to : rails.getFirst();
    }

    /** Returns the rail right before {@code to}: the last rail between, or {@code from}. */
    public RailPos last() {
        return rails.isEmpty() ? from : rails.getLast();
    }
}
