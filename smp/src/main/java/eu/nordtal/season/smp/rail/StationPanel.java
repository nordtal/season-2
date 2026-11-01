package eu.nordtal.season.smp.rail;

import eu.nordtal.season.packrendering.Glyphs;
import eu.nordtal.season.packrendering.menu.MenuFont;
import eu.nordtal.season.packrendering.menu.MenuTitle;
import eu.nordtal.season.packrendering.menu.SlotGeometry;
import eu.nordtal.season.papercommon.menu.MenuPalette;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.jspecify.annotations.Nullable;

/** Draws the station menu: the station's name, and below it the button that names it for those who may. */
public final class StationPanel {

    public static final int HEADING_ROW = 0;

    public static final int NAME_ROW = 1;

    public static final int INSET = 2;

    public static final int PILL_X = SlotGeometry.ORIGIN_X + INSET;

    public static final int PILL_WIDTH = SlotGeometry.COLUMNS * SlotGeometry.PITCH - 2 * INSET;

    private static final int ICON_X = PILL_X + 3;
    private static final int TEXT_X = ICON_X + 8 + 5;
    private static final int TEXT_RIGHT = PILL_X + PILL_WIDTH - 3;

    public static final List<Integer> HEADING_SLOTS = SlotGeometry.rows(HEADING_ROW, 1);

    public static final List<Integer> NAME_SLOTS = SlotGeometry.rows(NAME_ROW, 1);

    private StationPanel() {}

    /** Returns the window's rows: the heading, and the name button when {@code mayName}. */
    public static int rows(final boolean mayName) {
        return mayName ? 2 : 1;
    }

    /**
     * Returns the window as an inventory title.
     *
     * @param title  the translated window title
     * @param name   the station's name, or the line for a station without one
     * @param button the label of the name button, or null when the viewer may not name the station
     */
    public static Component title(final Component title, final String name, final @Nullable String button) {
        final MenuTitle.Canvas canvas = MenuTitle.onPlain(rows(button != null));
        canvas.rowArt(Glyphs.GUI_ROW_PILL_DARK, HEADING_ROW, PILL_X, null);
        canvas.rowArt(Glyphs.GUI_ROW_ICON_POI, HEADING_ROW, ICON_X, MenuPalette.INK);
        canvas.rowText(MenuFont.fit(name, TEXT_RIGHT - TEXT_X), HEADING_ROW, TEXT_X, MenuPalette.INK);
        if (button != null) {
            canvas.rowArt(Glyphs.GUI_ROW_PILL, NAME_ROW, PILL_X, null);
            canvas.rowText(MenuFont.fit(button, TEXT_RIGHT - TEXT_X), NAME_ROW, TEXT_X, MenuPalette.INK);
        }
        return canvas.build(title);
    }
}
