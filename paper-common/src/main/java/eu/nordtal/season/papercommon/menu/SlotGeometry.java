package eu.nordtal.season.papercommon.menu;

/** The pixel position of a chest slot in the window. A cell's origin is its top-left shadow pixel. */
public final class SlotGeometry {

    /** The x of slot column 0's cell. */
    public static final int ORIGIN_X = 7;

    /** The y of slot row 0's cell, below the 17 px title strip. */
    public static final int ORIGIN_Y = 17;

    /** One slot's outer size, and the distance between two. */
    public static final int PITCH = 18;

    /** Slots per chest row. */
    public static final int COLUMNS = 9;

    private SlotGeometry() {}

    public static int x(final int column) {
        return ORIGIN_X + column * PITCH;
    }

    public static int y(final int row) {
        return ORIGIN_Y + row * PITCH;
    }

    public static int slot(final int column, final int row) {
        if (column < 0 || column >= COLUMNS || row < 0) {
            throw new IllegalArgumentException("no slot at column " + column + ", row " + row);
        }
        return row * COLUMNS + column;
    }

    public static int column(final int slot) {
        return slot % COLUMNS;
    }

    public static int row(final int slot) {
        return slot / COLUMNS;
    }
}
