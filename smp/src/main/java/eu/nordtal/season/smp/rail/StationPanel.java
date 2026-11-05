package eu.nordtal.season.smp.rail;

import eu.nordtal.season.packrendering.Glyphs;
import eu.nordtal.season.packrendering.menu.MenuFont;
import eu.nordtal.season.packrendering.menu.MenuTitle;
import eu.nordtal.season.packrendering.menu.SlotGeometry;
import eu.nordtal.season.papercommon.menu.MenuPalette;
import java.util.List;
import java.util.stream.IntStream;
import net.kyori.adventure.text.Component;
import org.jspecify.annotations.Nullable;

/**
 * Draws the station menu: its name, and below it the buttons that name and hide it, for those who may.
 * The buttons stand side by side on the gold plates of the other menus, each on the narrowest that holds its labels.
 */
public final class StationPanel {

    public static final int HEADING_ROW = 0;

    public static final int BUTTON_ROW = 1;

    public static final int INSET = 2;

    public static final int PILL_X = SlotGeometry.ORIGIN_X + INSET;

    public static final int PILL_WIDTH = SlotGeometry.COLUMNS * SlotGeometry.PITCH - 2 * INSET;

    private static final int ICON_X = PILL_X + 3;
    private static final int TEXT_X = ICON_X + 8 + 5;
    private static final int TEXT_RIGHT = PILL_X + PILL_WIDTH - 3;

    public static final List<Integer> HEADING_SLOTS = SlotGeometry.rows(HEADING_ROW, 1);

    /** The name button's first column. Seven cells hold both buttons, so one cell is left free on either side. */
    private static final int NAME_COLUMN = 1;

    /** The cells under the name button: four, as {@code GUI_ROW_BUTTON_TAKE}, which the German rename label needs. */
    private static final int NAME_CELLS = 4;

    private static final int VISIBILITY_COLUMN = NAME_COLUMN + NAME_CELLS;

    /** The cells under the visibility button: three, as the {@code GUI_ROW_BUTTON_CONFIRM} plate. */
    private static final int VISIBILITY_CELLS = 3;

    public static final List<Integer> NAME_SLOTS = cells(NAME_COLUMN, NAME_CELLS);

    public static final List<Integer> VISIBILITY_SLOTS = cells(VISIBILITY_COLUMN, VISIBILITY_CELLS);

    private StationPanel() {}

    /** Returns the window's rows: the heading, and the row of buttons when {@code mayChange}. */
    public static int rows(final boolean mayChange) {
        return mayChange ? 2 : 1;
    }

    /** The labels of the name and visibility buttons, for a viewer who may change the station. */
    public record Buttons(String name, String visibility) {}

    /**
     * Returns the window as an inventory title.
     *
     * @param title  the translated window title
     * @param name   the station's name, or the line for a station without one
     * @param buttons the labels of the buttons, or null when the viewer may not change the station
     */
    public static Component title(final Component title, final String name, final @Nullable Buttons buttons) {
        final MenuTitle.Canvas canvas = MenuTitle.onPlain(rows(buttons != null));
        canvas.rowArt(Glyphs.GUI_ROW_PILL_DARK, HEADING_ROW, PILL_X, null);
        canvas.rowArt(Glyphs.GUI_ROW_ICON_POI, HEADING_ROW, ICON_X, MenuPalette.INK);
        canvas.rowText(MenuFont.fit(name, TEXT_RIGHT - TEXT_X), HEADING_ROW, TEXT_X, MenuPalette.INK);
        if (buttons != null) {
            button(canvas, Glyphs.GUI_ROW_BUTTON_TAKE, NAME_COLUMN, NAME_CELLS, buttons.name());
            button(canvas, Glyphs.GUI_ROW_BUTTON_CONFIRM, VISIBILITY_COLUMN, VISIBILITY_CELLS, buttons.visibility());
        }
        return canvas.build(title);
    }

    private static void button(
            final MenuTitle.Canvas canvas, final String plate, final int column, final int cells, final String label) {
        canvas.rowButton(
                plate,
                BUTTON_ROW,
                SlotGeometry.x(column) + INSET,
                cells * SlotGeometry.PITCH - 2 * INSET,
                label,
                MenuPalette.INK);
    }

    private static List<Integer> cells(final int column, final int count) {
        return IntStream.range(column, column + count)
                .mapToObj(cell -> SlotGeometry.slot(cell, BUTTON_ROW))
                .toList();
    }
}
