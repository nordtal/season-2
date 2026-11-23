package eu.nordtal.season.packrendering.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * Lays out a two-column card grid inside a dialog's content area, with or without a scroll column on the right.
 * When the scroll column is absent the cards take its room; an odd total keeps the last card on the left.
 */
public final class CardGrid {

    /** The two columns of every card grid. */
    public static final int COLUMNS = 2;

    /** The default horizontal and vertical gap between sibling cards. */
    public static final int GAP = BodyLine.SIDE_GAP;

    /** One card's rectangle in content coordinates. */
    public record Slot(int index, int column, int row, int x, int y, int width, int height) {}

    private final int originX;
    private final int originY;
    private final int totalWidth;
    private final int cardHeight;
    private final int rowGap;
    private final boolean scrollable;

    private CardGrid(
            final int originX,
            final int originY,
            final int totalWidth,
            final int cardHeight,
            final int rowGap,
            final boolean scrollable) {
        if (totalWidth <= 0 || cardHeight <= 0) {
            throw new IllegalArgumentException("grid dimensions must be positive");
        }
        this.originX = originX;
        this.originY = originY;
        this.totalWidth = totalWidth;
        this.cardHeight = cardHeight;
        this.rowGap = rowGap;
        this.scrollable = scrollable;
    }

    /** Creates a two-column grid starting at ({@code x}, {@code y}) across {@code totalWidth} content pixels. */
    public static CardGrid of(
            final int x,
            final int y,
            final int totalWidth,
            final int cardHeight,
            final int rowGap,
            final boolean scrollable) {
        return new CardGrid(x, y, totalWidth, cardHeight, rowGap, scrollable);
    }

    /** The width left for the two card columns, after reserving the scroll column when {@link #scrollable()}. */
    public int cardsWidth() {
        return scrollable ? totalWidth - ScrollState.COLUMN_WIDTH - GAP : totalWidth;
    }

    /** The width of each card column, split evenly across {@link #cardsWidth()}. */
    public int cardWidth() {
        return (cardsWidth() - GAP) / COLUMNS;
    }

    /** The content x of the scroll column at the grid's right edge. */
    public int scrollColumnX() {
        return originX + totalWidth - ScrollState.COLUMN_WIDTH;
    }

    /** Whether a scroll column sits on the right. */
    public boolean scrollable() {
        return scrollable;
    }

    /** Returns the number of rows needed to hold {@code itemCount} items in two columns. */
    public static int totalRows(final int itemCount) {
        return Math.ceilDiv(Math.max(0, itemCount), COLUMNS);
    }

    /**
     * Returns the visible card slots for {@code itemCount} items from {@code rowOffset}.
     * Up to {@code visibleRows} rows are returned, and an odd last item sits in column 0 (left).
     */
    public List<Slot> visible(final int itemCount, final int rowOffset, final int visibleRows) {
        final int start = Math.max(0, rowOffset) * COLUMNS;
        final int end = Math.min(itemCount, start + visibleRows * COLUMNS);
        final int width = cardWidth();
        final int rightX = originX + cardsWidth() - width;
        final List<Slot> slots = new ArrayList<>(Math.max(0, end - start));
        for (int index = start; index < end; index++) {
            final int relative = index - start;
            final int column = relative % COLUMNS;
            final int row = relative / COLUMNS;
            final int x = column == 0 ? originX : rightX;
            final int y = originY + row * (cardHeight + rowGap);
            slots.add(new Slot(index, column, row, x, y, width, cardHeight));
        }
        return List.copyOf(slots);
    }
}
