package eu.nordtal.season.smp.item;

import eu.nordtal.season.packrendering.menu.SlotGeometry;
import eu.nordtal.season.packrendering.ui.BodyLine;
import eu.nordtal.season.packrendering.ui.ChestTitle;
import eu.nordtal.season.packrendering.ui.Colours;
import eu.nordtal.season.packrendering.ui.Look;
import eu.nordtal.season.packrendering.ui.Mark;
import eu.nordtal.season.packrendering.ui.Symbol;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;

/**
 * Draws the paginated Nordtal custom items chest window.
 * A single page uses only the content rows it needs; multiple pages add a bottom pager row.
 */
public final class ItemsPanel {

    /** Maximum content rows when a bottom pager row is shown. */
    public static final int PAGED_CONTENT_ROWS = ChestTitle.MAX_ROWS - 1;

    /** Maximum items that fit without a pager row. */
    public static final int SINGLE_PAGE_CAPACITY = ChestTitle.MAX_ROWS * SlotGeometry.COLUMNS;

    /** Items per page when paging is active. */
    public static final int ITEMS_PER_PAGE = PAGED_CONTENT_ROWS * SlotGeometry.COLUMNS;

    private static final int PREV_COLUMN = 0;
    private static final int PREV_CELLS = 3;
    private static final int NEXT_COLUMN = 6;
    private static final int NEXT_CELLS = 3;

    private ItemsPanel() {}

    /** Returns whether {@code totalItems} requires more than one page. */
    public static boolean needsPaging(final int totalItems) {
        return totalItems > SINGLE_PAGE_CAPACITY;
    }

    /** Returns the number of pages for {@code totalItems}, at least 1. */
    public static int pageCount(final int totalItems) {
        if (!needsPaging(totalItems)) {
            return 1;
        }
        return (totalItems + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE;
    }

    /** Returns the item capacity of one page for a menu holding {@code totalItems}. */
    public static int pageSize(final int totalItems) {
        return needsPaging(totalItems) ? ITEMS_PER_PAGE : SINGLE_PAGE_CAPACITY;
    }

    /** Returns the total chest rows (1 to 6) for a menu holding {@code totalItems}. */
    public static int rows(final int totalItems) {
        if (needsPaging(totalItems)) {
            return ChestTitle.MAX_ROWS;
        }
        final int content = (Math.max(1, totalItems) + SlotGeometry.COLUMNS - 1) / SlotGeometry.COLUMNS;
        return Math.max(1, Math.min(ChestTitle.MAX_ROWS, content));
    }

    /** Returns the content rows on a page for a menu holding {@code totalItems}. */
    public static int contentRows(final int totalItems) {
        return needsPaging(totalItems) ? PAGED_CONTENT_ROWS : rows(totalItems);
    }

    /** Returns the slot indices of the previous-page button when paging is active. */
    public static List<Integer> previousSlots() {
        return rangeSlots(PREV_COLUMN, PREV_CELLS, PAGED_CONTENT_ROWS);
    }

    /** Returns the slot indices of the next-page button when paging is active. */
    public static List<Integer> nextSlots() {
        return rangeSlots(NEXT_COLUMN, NEXT_CELLS, PAGED_CONTENT_ROWS);
    }

    private static List<Integer> rangeSlots(final int startColumn, final int cells, final int row) {
        final List<Integer> slots = new ArrayList<>(cells);
        for (int column = startColumn; column < startColumn + cells; column++) {
            slots.add(SlotGeometry.slot(column, row));
        }
        return List.copyOf(slots);
    }

    /**
     * Draws the chest title with slot frames for every content slot and, when paged, previous/next plates.
     *
     * @param name the window title
     * @param totalItems total registered items
     * @param page zero-based current page
     * @param previous label for the previous-page button
     * @param next label for the next-page button
     */
    public static Component title(
            final String name, final int totalItems, final int page, final String previous, final String next) {
        final boolean paged = needsPaging(totalItems);
        final int pages = pageCount(totalItems);
        final int rows = rows(totalItems);
        final int content = contentRows(totalItems);
        final List<Mark> marks = paged ? List.of(Mark.small((page + 1) + "/" + pages, Colours.SOFT)) : List.of();
        final ChestTitle title = ChestTitle.of(rows).boldHeader(Symbol.CHEST, name, marks);
        for (int row = 0; row < content; row++) {
            for (int column = 0; column < SlotGeometry.COLUMNS; column++) {
                title.slot(column, row);
            }
        }
        if (paged) {
            final int footer = PAGED_CONTENT_ROWS;
            final Look prevLook = page > 0 ? Look.NORMAL : Look.DISABLED;
            final Look nextLook = page + 1 < pages ? Look.NORMAL : Look.DISABLED;
            title.plate(PREV_COLUMN, footer, PREV_CELLS, prevLook)
                    .label(PREV_COLUMN, footer, PREV_CELLS, Symbol.LEFT, previous, prevLook)
                    .plate(NEXT_COLUMN, footer, NEXT_CELLS, nextLook)
                    .label(NEXT_COLUMN, footer, NEXT_CELLS, Symbol.RIGHT, next, nextLook);
        }
        final String narration = paged ? name + " " + (page + 1) + "/" + pages : name;
        return title.build(BodyLine.silent(narration));
    }
}
