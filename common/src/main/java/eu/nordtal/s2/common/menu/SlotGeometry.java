package eu.nordtal.s2.common.menu;

/**
 * Where a chest slot is, in the window's own pixels.
 *
 * Mirrors {@code resource-pack/tools/generate_gui_panels.py}; {@code MenuTitleTest} holds the two
 * together. A cell's origin is its top-left shadow pixel, one up and left of the item area.
 */
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

    /** @return the x of column {@code column}'s cell */
    public static int x(final int column) {
        return ORIGIN_X + column * PITCH;
    }

    /** @return the y of row {@code row}'s cell */
    public static int y(final int row) {
        return ORIGIN_Y + row * PITCH;
    }

    /** @return the raw slot index of ({@code column}, {@code row}) in a chest inventory */
    public static int slot(final int column, final int row) {
        if (column < 0 || column >= COLUMNS || row < 0) {
            throw new IllegalArgumentException("no slot at column " + column + ", row " + row);
        }
        return row * COLUMNS + column;
    }

    /** @return the column of raw slot {@code slot} */
    public static int column(final int slot) {
        return slot % COLUMNS;
    }

    /** @return the row of raw slot {@code slot} */
    public static int row(final int slot) {
        return slot / COLUMNS;
    }
}
