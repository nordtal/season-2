package eu.nordtal.season.smp.navigate;

import eu.nordtal.season.packrendering.Glyphs;
import eu.nordtal.season.packrendering.menu.MenuFont;
import eu.nordtal.season.packrendering.menu.MenuTitle;
import eu.nordtal.season.packrendering.menu.SlotGeometry;
import eu.nordtal.season.papercommon.menu.MenuPalette;
import java.util.List;
import net.kyori.adventure.text.Component;

/** {@code NavigatePanelTest} checks that the geometry matches the resource pack. */
public final class NavigatePanel {

    public static final int ROWS = MenuTitle.MAX_ROWS;

    public static final int ENTRIES_PER_PAGE = ROWS - 1;

    public static final int CONTROL_ROW = ROWS - 1;

    public static final int INSET = 2;

    public static final int PILL_X = SlotGeometry.ORIGIN_X + INSET;

    public static final int PILL_WIDTH = SlotGeometry.COLUMNS * SlotGeometry.PITCH - 2 * INSET;

    private static final int ICON_X = PILL_X + 3;

    private static final int ICON_SIZE = 8;

    private static final int NAME_X = ICON_X + ICON_SIZE + 5;

    private static final int DISTANCE_RIGHT = PILL_X + PILL_WIDTH - 3;

    private static final int NAME_GAP = 4;

    private static final int STOP_X = PILL_X;
    private static final int STOP_WIDTH = 52;
    private static final int STOP_ICON_X = STOP_X + 3;
    private static final int STOP_LABEL_X = STOP_ICON_X + ICON_SIZE + 3;

    private static final int BUTTON_WIDTH = SlotGeometry.PITCH - 2 * INSET;
    private static final int PREV_X = SlotGeometry.x(6) + INSET;
    private static final int PAGE_X = SlotGeometry.x(7);
    private static final int NEXT_X = SlotGeometry.x(8) + INSET;

    /** The slots under the stop plate. They share one tooltip and one click. */
    public static final List<Integer> STOP_SLOTS = List.of(
            SlotGeometry.slot(0, CONTROL_ROW), SlotGeometry.slot(1, CONTROL_ROW), SlotGeometry.slot(2, CONTROL_ROW));

    public static final int PREV_SLOT = SlotGeometry.slot(6, CONTROL_ROW);
    public static final int PAGE_SLOT = SlotGeometry.slot(7, CONTROL_ROW);
    public static final int NEXT_SLOT = SlotGeometry.slot(8, CONTROL_ROW);

    private NavigatePanel() {}

    public static String icon(final NavigationTarget.Kind kind) {
        return switch (kind) {
            case WORLD_SPAWN -> Glyphs.GUI_ROW_ICON_SPAWN;
            case LAST_DEATH -> Glyphs.GUI_ROW_ICON_DEATH;
            case POI -> Glyphs.GUI_ROW_ICON_POI;
        };
    }

    /**
     * One destination row.
     *
     * @param icon     one of {@code Glyphs.GUI_ROW_ICON_*}
     * @param name     the destination name, which the panel folds and shortens
     * @param distance the text at the right edge of the row
     * @param active   whether the player is pointed to this destination
     */
    public record Entry(String icon, String name, String distance, boolean active) {}

    /**
     * Returns the window as an inventory title.
     *
     * @param title   the window title, already translated
     * @param entries up to {@link #ENTRIES_PER_PAGE} destinations, top row first
     * @param stop    the label of the stop button, at most 52 pixels wide
     * @param page    the text between the page buttons, such as {@code 2/3}
     * @param hasPrev whether a page precedes this one
     * @param hasNext whether a page follows this one
     */
    public static Component title(
            final Component title,
            final List<Entry> entries,
            final String stop,
            final String page,
            final boolean hasPrev,
            final boolean hasNext) {
        if (entries.size() > ENTRIES_PER_PAGE) {
            throw new IllegalArgumentException(
                    "a page holds " + ENTRIES_PER_PAGE + " destinations, not " + entries.size());
        }
        final MenuTitle.Canvas canvas = MenuTitle.onPlain(ROWS);

        for (int row = 0; row < entries.size(); row++) {
            final Entry entry = entries.get(row);
            canvas.rowArt(Glyphs.GUI_ROW_PILL, row, PILL_X, null);
            canvas.rowArt(entry.icon(), row, ICON_X, MenuPalette.INK);

            final String distance = MenuFont.fold(entry.distance());
            final int distanceWidth = MenuFont.width(distance);
            canvas.rowText(
                    MenuFont.fit(entry.name(), DISTANCE_RIGHT - distanceWidth - NAME_GAP - NAME_X),
                    row,
                    NAME_X,
                    MenuPalette.INK);
            if (distanceWidth > 0) {
                canvas.rowTextRight(distance, row, DISTANCE_RIGHT, MenuPalette.SOFT);
            }

            // The frame of the active destination is drawn last.
            if (entry.active()) {
                canvas.rowArt(Glyphs.GUI_ROW_FRAME, row, PILL_X, null);
            }
        }

        canvas.rowArt(Glyphs.GUI_ROW_BUTTON_WIDE, CONTROL_ROW, STOP_X, null);
        canvas.rowArt(Glyphs.GUI_ROW_ICON_STOP, CONTROL_ROW, STOP_ICON_X, MenuPalette.ON_PLATE);
        canvas.rowText(
                MenuFont.fit(stop, STOP_X + STOP_WIDTH - 3 - STOP_LABEL_X),
                CONTROL_ROW,
                STOP_LABEL_X,
                MenuPalette.ON_PLATE);

        pageButton(canvas, PREV_X, Glyphs.GUI_ROW_ICON_PREV, hasPrev);
        final String label = MenuFont.fold(page);
        canvas.rowText(label, CONTROL_ROW, PAGE_X + (SlotGeometry.PITCH - MenuFont.width(label)) / 2, MenuPalette.SOFT);
        pageButton(canvas, NEXT_X, Glyphs.GUI_ROW_ICON_NEXT, hasNext);

        return canvas.build(title);
    }

    /** Draws a page button, greyed when {@code enabled} is false. */
    private static void pageButton(
            final MenuTitle.Canvas canvas, final int x, final String arrow, final boolean enabled) {
        canvas.rowArt(enabled ? Glyphs.GUI_ROW_BUTTON_SMALL : Glyphs.GUI_ROW_BUTTON_SMALL_OFF, CONTROL_ROW, x, null);
        canvas.rowArt(
                arrow,
                CONTROL_ROW,
                x + (BUTTON_WIDTH - ICON_SIZE) / 2,
                enabled ? MenuPalette.INK : MenuPalette.DISABLED);
    }
}
