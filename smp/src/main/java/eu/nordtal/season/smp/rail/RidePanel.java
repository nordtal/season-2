package eu.nordtal.season.smp.rail;

import eu.nordtal.season.packrendering.Glyphs;
import eu.nordtal.season.packrendering.menu.MenuFont;
import eu.nordtal.season.packrendering.menu.MenuTitle;
import eu.nordtal.season.packrendering.menu.SlotGeometry;
import eu.nordtal.season.papercommon.menu.MenuPalette;
import java.util.List;
import net.kyori.adventure.text.Component;

/** Draws the ride menu: one row per station with its favourite star, and the page buttons below them when paged. */
public final class RidePanel {

    /** The most stations a page lists; the sixth row holds the page buttons. */
    public static final int MOST_PER_PAGE = MenuTitle.MAX_ROWS - 1;

    /** The distance in pixels between a drawn element and the slot cells it covers. */
    public static final int INSET = 2;

    public static final int PILL_X = SlotGeometry.ORIGIN_X + INSET;

    /** The width of {@link Glyphs#GUI_ROW_PILL_LONG}: the station's cells 0 to 7, up to the star. */
    public static final int PILL_WIDTH = 8 * SlotGeometry.PITCH - 2 * INSET;

    /** The width of a full row's plate, for the line when no station is listed. */
    public static final int WIDE_WIDTH = SlotGeometry.COLUMNS * SlotGeometry.PITCH - 2 * INSET;

    /** The column of the star that marks a favourite. */
    public static final int STAR_COLUMN = 8;

    /** The cells of a station row that its plate covers. */
    public static final int STATION_COLUMNS = 8;

    private static final int ICON_SIZE = 8;
    private static final int ICON_X = PILL_X + 3;
    private static final int NAME_X = ICON_X + ICON_SIZE + 5;
    private static final int DISTANCE_RIGHT = PILL_X + PILL_WIDTH - 3;
    private static final int NAME_GAP = 4;
    private static final int BUTTON_WIDTH = SlotGeometry.PITCH - 2 * INSET;
    private static final int STAR_X = SlotGeometry.x(STAR_COLUMN) + INSET;
    private static final int PREV_COLUMN = 6;
    private static final int PAGE_COLUMN = 7;
    private static final int NEXT_COLUMN = 8;

    private RidePanel() {}

    /**
     * One station row.
     *
     * @param name      the station's name, which the panel folds and shortens
     * @param distance  the text at the right edge of the plate
     * @param favourite whether the star is lit
     */
    public record Row(String name, String distance, boolean favourite) {}

    /** Returns the stations one page lists, which the window's rows follow. */
    public static int stationRows(final int count, final int perPage) {
        return pages(count, perPage) > 1 ? perPage : Math.max(1, count);
    }

    /** Returns the window's rows: one per listed station, and one for the page buttons when there are pages. */
    public static int rows(final int count, final int perPage) {
        return stationRows(count, perPage) + (pages(count, perPage) > 1 ? 1 : 0);
    }

    public static int pages(final int count, final int perPage) {
        return Math.max(1, (count + perPage - 1) / perPage);
    }

    /** Returns the slots a station row's plate covers. */
    public static List<Integer> stationSlots(final int row) {
        return SlotGeometry.rows(row, 1).subList(0, STATION_COLUMNS);
    }

    public static int starSlot(final int row) {
        return SlotGeometry.slot(STAR_COLUMN, row);
    }

    public static int previousSlot(final int stationRows) {
        return SlotGeometry.slot(PREV_COLUMN, stationRows);
    }

    public static int nextSlot(final int stationRows) {
        return SlotGeometry.slot(NEXT_COLUMN, stationRows);
    }

    /**
     * Returns the window as an inventory title.
     *
     * @param title       the translated window title
     * @param rows        the stations of this page, top row first; none draws {@code none} instead
     * @param none        the line when no station is listed
     * @param stationRows the rows above the page buttons, from {@link #stationRows}
     * @param page        the text between the page buttons, such as {@code 2/3}
     * @param paged       whether the page buttons are drawn
     * @param hasPrev whether a page precedes this one
     * @param hasNext whether a page follows this one
     */
    public static Component title(
            final Component title,
            final List<Row> rows,
            final String none,
            final int stationRows,
            final String page,
            final boolean paged,
            final boolean hasPrev,
            final boolean hasNext) {
        if (rows.size() > stationRows || stationRows > MOST_PER_PAGE) {
            throw new IllegalArgumentException("a page of " + stationRows + " rows cannot list " + rows.size());
        }
        final MenuTitle.Canvas canvas = MenuTitle.onPlain(stationRows + (paged ? 1 : 0));
        if (rows.isEmpty()) {
            canvas.rowArt(Glyphs.GUI_ROW_PILL_DARK, 0, PILL_X, null);
            canvas.rowText(MenuFont.fit(none, PILL_X + WIDE_WIDTH - 3 - ICON_X), 0, ICON_X, MenuPalette.INK);
        }
        for (int row = 0; row < rows.size(); row++) {
            station(canvas, row, rows.get(row));
        }
        if (paged) {
            pageButton(canvas, stationRows, PREV_COLUMN, Glyphs.GUI_ROW_ICON_PREV, hasPrev);
            final String label = MenuFont.fold(page);
            canvas.rowText(
                    label,
                    stationRows,
                    SlotGeometry.x(PAGE_COLUMN) + (SlotGeometry.PITCH - MenuFont.width(label)) / 2,
                    MenuPalette.SOFT);
            pageButton(canvas, stationRows, NEXT_COLUMN, Glyphs.GUI_ROW_ICON_NEXT, hasNext);
        }
        return canvas.build(title);
    }

    private static void station(final MenuTitle.Canvas canvas, final int row, final Row station) {
        canvas.rowArt(Glyphs.GUI_ROW_PILL_LONG, row, PILL_X, null);
        canvas.rowArt(Glyphs.GUI_ROW_ICON_POI, row, ICON_X, MenuPalette.INK);
        final String distance = MenuFont.fold(station.distance());
        final int distanceWidth = MenuFont.width(distance);
        canvas.rowText(
                MenuFont.fit(station.name(), DISTANCE_RIGHT - distanceWidth - NAME_GAP - NAME_X),
                row,
                NAME_X,
                MenuPalette.INK);
        canvas.rowTextRight(distance, row, DISTANCE_RIGHT, MenuPalette.SOFT);
        canvas.rowArt(Glyphs.GUI_ROW_BUTTON_SMALL, row, STAR_X, null);
        canvas.rowArt(
                Glyphs.GUI_ROW_ICON_FAVOURITE,
                row,
                STAR_X + (BUTTON_WIDTH - ICON_SIZE) / 2,
                station.favourite() ? MenuPalette.INK : MenuPalette.DISABLED);
    }

    /** Draws a page button, greyed when {@code enabled} is false. */
    private static void pageButton(
            final MenuTitle.Canvas canvas, final int row, final int column, final String arrow, final boolean enabled) {
        final int x = SlotGeometry.x(column) + INSET;
        canvas.rowArt(enabled ? Glyphs.GUI_ROW_BUTTON_SMALL : Glyphs.GUI_ROW_BUTTON_SMALL_OFF, row, x, null);
        canvas.rowArt(arrow, row, x + (BUTTON_WIDTH - ICON_SIZE) / 2, enabled ? MenuPalette.INK : MenuPalette.DISABLED);
    }
}
