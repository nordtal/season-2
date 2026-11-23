package eu.nordtal.season.packrendering.ui;

/**
 * The vertical scroll state of a dialog list or card grid, in whole rows.
 * Arrows move one row; clicking the track above or below the flat position strip moves one view.
 */
public record ScrollState(int totalRows, int visibleRows, int offset) {

    /** The scroll column's width: one 17 px plate wide. */
    public static final int COLUMN_WIDTH = BodyLine.PLATE;

    /** The standard track height on a 280 by 161 panel: 112 px column minus two 17 px plates and two 3 px gaps. */
    public static final int TRACK_HEIGHT = 72;

    /** The inner drawable height of the standard track, inside its 1 px top and bottom borders and 1 px margin. */
    public static final int TRACK_INNER = TRACK_HEIGHT - 4;

    /** The flat position strip's width inside the 17 px track: 1 px border and 1 px margin on each side. */
    public static final int STRIP_WIDTH = 13;

    /** The step to which the strip's top and height align, matching the pack's strip glyphs. */
    public static final int STRIP_STEP = 4;

    /** The position strip's vertical span inside the track's inner area, counted from 0. */
    public record Strip(int top, int height) {

        /** The first row below the position strip. */
        public int bottom() {
            return top + height;
        }
    }

    public ScrollState {
        if (totalRows < 0) {
            throw new IllegalArgumentException("totalRows must be non-negative, not " + totalRows);
        }
        if (visibleRows < 1) {
            throw new IllegalArgumentException("visibleRows must be positive, not " + visibleRows);
        }
        final int max = Math.max(0, totalRows - visibleRows);
        offset = Math.max(0, Math.min(offset, max));
    }

    /** Creates a scroll state for {@code totalRows}, {@code visibleRows} at once, clamped at {@code offset}. */
    public static ScrollState of(final int totalRows, final int visibleRows, final int offset) {
        return new ScrollState(totalRows, visibleRows, offset);
    }

    /** Returns whether the content exceeds one view and needs a scroll column. */
    public boolean scrollable() {
        return totalRows > visibleRows;
    }

    /** Returns the highest valid row offset. */
    public int maxOffset() {
        return Math.max(0, totalRows - visibleRows);
    }

    /** Returns whether the up arrow is active. */
    public boolean canScrollUp() {
        return offset > 0;
    }

    /** Returns whether the down arrow is active. */
    public boolean canScrollDown() {
        return offset < maxOffset();
    }

    /** Returns the row offset one step up. */
    public int stepUp() {
        return Math.max(0, offset - 1);
    }

    /** Returns the row offset one step down. */
    public int stepDown() {
        return Math.min(maxOffset(), offset + 1);
    }

    /** Returns the row offset one view up. */
    public int pageUp() {
        return Math.max(0, offset - visibleRows);
    }

    /** Returns the row offset one view down. */
    public int pageDown() {
        return Math.min(maxOffset(), offset + visibleRows);
    }

    /**
     * Computes the flat position strip's top and height inside an inner track {@code innerHeight} pixels tall.
     * Both align to {@link #STRIP_STEP} so the pack's 4, 8 and 16 px strips draw it without gaps.
     */
    public Strip strip(final int innerHeight) {
        final int alignedInner = (innerHeight / STRIP_STEP) * STRIP_STEP;
        if (!scrollable() || alignedInner <= STRIP_STEP) {
            return new Strip(0, Math.max(STRIP_STEP, alignedInner));
        }
        final int rawHeight = Math.round((float) alignedInner * visibleRows / totalRows);
        final int snappedHeight = Math.max(
                2 * STRIP_STEP, Math.min(alignedInner, Math.round((float) rawHeight / STRIP_STEP) * STRIP_STEP));
        final int travel = alignedInner - snappedHeight;
        final int rawTop = Math.round((float) travel * offset / maxOffset());
        final int snappedTop = Math.max(0, Math.min(travel, Math.round((float) rawTop / STRIP_STEP) * STRIP_STEP));
        return new Strip(snappedTop, snappedHeight);
    }
}
