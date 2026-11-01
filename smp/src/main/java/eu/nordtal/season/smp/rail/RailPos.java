package eu.nordtal.season.smp.rail;

import java.util.Comparator;

/** The block position of a rail within one world. */
public record RailPos(int x, int y, int z) implements Comparable<RailPos> {

    private static final Comparator<RailPos> ORDER =
            Comparator.comparingInt(RailPos::x).thenComparingInt(RailPos::y).thenComparingInt(RailPos::z);

    /** Returns the position one step towards {@code heading} and {@code dy} blocks up. */
    public RailPos step(final Heading heading, final int dy) {
        return new RailPos(x + heading.dx(), y + dy, z + heading.dz());
    }

    public RailPos below() {
        return new RailPos(x, y - 1, z);
    }

    @Override
    public int compareTo(final RailPos other) {
        return ORDER.compare(this, other);
    }
}
