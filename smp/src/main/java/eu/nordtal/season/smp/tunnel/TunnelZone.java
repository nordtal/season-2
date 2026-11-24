package eu.nordtal.season.smp.tunnel;

import org.jspecify.annotations.Nullable;

/** The 7 interactive directional zones on an active 5x5 tunnel front face (cols -2..+2, rows 0..4). */
public enum TunnelZone {
    CENTER(-1, 1, 3, 3, "center", 0, 0, 0),
    TOP(-1, 4, 3, 1, "top", 0, 1, 0),
    BOTTOM(-1, 0, 3, 1, "bottom", 0, -1, 0),
    LEFT(-2, 1, 1, 3, "left", -1, 0, 0),
    RIGHT(2, 1, 1, 3, "right", 1, 0, 0),
    TURN_LEFT(-2, 0, 1, 1, "turn_left", 0, 0, -1),
    TURN_RIGHT(2, 0, 1, 1, "turn_right", 0, 0, 1);

    private final int colMin;
    private final int rowMin;
    private final int width;
    private final int height;
    private final String modelName;
    private final int shiftCol;
    private final int shiftRow;
    private final int turnDirection;

    TunnelZone(
            final int colMin,
            final int rowMin,
            final int width,
            final int height,
            final String modelName,
            final int shiftCol,
            final int shiftRow,
            final int turnDirection) {
        this.colMin = colMin;
        this.rowMin = rowMin;
        this.width = width;
        this.height = height;
        this.modelName = modelName;
        this.shiftCol = shiftCol;
        this.shiftRow = shiftRow;
        this.turnDirection = turnDirection;
    }

    public int colMin() {
        return colMin;
    }

    public int rowMin() {
        return rowMin;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public String modelName() {
        return modelName;
    }

    public int shiftCol() {
        return shiftCol;
    }

    public int shiftRow() {
        return shiftRow;
    }

    public boolean isTurn() {
        return turnDirection != 0;
    }

    public boolean isTurnLeft() {
        return turnDirection < 0;
    }

    /** Returns the zone covering {@code (col, row)}, or null for the two top corners or out-of-bounds coordinates. */
    public static @Nullable TunnelZone at(final int col, final int row) {
        for (final TunnelZone zone : values()) {
            if (col >= zone.colMin
                    && col < zone.colMin + zone.width
                    && row >= zone.rowMin
                    && row < zone.rowMin + zone.height) {
                return zone;
            }
        }
        return null;
    }
}
