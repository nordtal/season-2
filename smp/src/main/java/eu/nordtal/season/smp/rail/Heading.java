package eu.nordtal.season.smp.rail;

/** One of the four horizontal directions a rail can lead in. */
public enum Heading {
    NORTH(0, -1),
    EAST(1, 0),
    SOUTH(0, 1),
    WEST(-1, 0);

    private final int dx;
    private final int dz;

    Heading(final int dx, final int dz) {
        this.dx = dx;
        this.dz = dz;
    }

    public int dx() {
        return dx;
    }

    public int dz() {
        return dz;
    }

    public Heading opposite() {
        return switch (this) {
            case NORTH -> SOUTH;
            case EAST -> WEST;
            case SOUTH -> NORTH;
            case WEST -> EAST;
        };
    }

    /** Returns the heading from {@code from} to {@code to}, two positions one step apart horizontally. */
    public static Heading between(final RailPos from, final RailPos to) {
        final int dx = Integer.signum(to.x() - from.x());
        final int dz = Integer.signum(to.z() - from.z());
        for (final Heading heading : values()) {
            if (heading.dx == dx && heading.dz == dz) {
                return heading;
            }
        }
        throw new IllegalArgumentException(from + " and " + to + " are not one step apart");
    }
}
