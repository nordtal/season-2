package eu.nordtal.season.smp.rail;

import org.jspecify.annotations.Nullable;

/** The shape of a rail, named as Paper names it: the two headings it leads in, and the one it rises towards. */
public enum RailShape {
    NORTH_SOUTH(Heading.NORTH, Heading.SOUTH, null),
    EAST_WEST(Heading.EAST, Heading.WEST, null),
    ASCENDING_EAST(Heading.EAST, Heading.WEST, Heading.EAST),
    ASCENDING_WEST(Heading.EAST, Heading.WEST, Heading.WEST),
    ASCENDING_NORTH(Heading.NORTH, Heading.SOUTH, Heading.NORTH),
    ASCENDING_SOUTH(Heading.NORTH, Heading.SOUTH, Heading.SOUTH),
    SOUTH_EAST(Heading.SOUTH, Heading.EAST, null),
    SOUTH_WEST(Heading.SOUTH, Heading.WEST, null),
    NORTH_WEST(Heading.NORTH, Heading.WEST, null),
    NORTH_EAST(Heading.NORTH, Heading.EAST, null);

    private final Heading one;
    private final Heading other;
    private final @Nullable Heading rises;

    RailShape(final Heading one, final Heading other, final @Nullable Heading rises) {
        this.one = one;
        this.other = other;
        this.rises = rises;
    }

    public boolean leadsTowards(final Heading heading) {
        return heading == one || heading == other;
    }

    public boolean risesTowards(final Heading heading) {
        return heading == rises;
    }

    /**
     * Returns the shape that joins the headings {@code a} and {@code b}, two different ones.
     *
     * @param rising the heading in which the next rail lies one block higher, or null when neither does
     */
    public static RailShape joining(final Heading a, final Heading b, final @Nullable Heading rising) {
        if (a == b) {
            throw new IllegalArgumentException("a rail joins two different headings, not " + a + " twice");
        }
        for (final RailShape shape : values()) {
            if (shape.leadsTowards(a) && shape.leadsTowards(b) && shape.rises == (b == a.opposite() ? rising : null)) {
                return shape;
            }
        }
        // A rising heading that is neither of the two ends leaves the rail flat.
        return joining(a, b, null);
    }
}
