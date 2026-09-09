package eu.nordtal.s2.smp.navigate;

import eu.nordtal.s2.common.Glyphs;
import eu.nordtal.s2.common.menu.MenuFont;
import eu.nordtal.s2.common.menu.MenuPalette;
import eu.nordtal.s2.common.menu.MenuTitle;
import eu.nordtal.s2.common.menu.SlotGeometry;

import net.kyori.adventure.text.Component;

import java.util.List;

/**
 * Draws {@code /navigate}'s surface: five destinations, one per chest row, and a row of controls.
 *
 * <h2>What the picture is</h2>
 * Design {@code N1} from the owner's menu artifact, chosen on 2026-09-08. One entry per row, each a
 * full-width pill carrying a kind icon, the destination's name and - right-aligned - how far away
 * it is; the entry currently being navigated to wears a white frame. The bottom row is a red
 * {@code stop} plate on the left and a page back / page number / page forward group on the right.
 *
 * <h2>Why five per page and not forty-five</h2>
 * Because the name is the thing somebody is looking for. Ten entries fit if each is half a window
 * wide, and at that width "Baeckerei am Fluss" is "BAECKERE.." - so the menu would be a list of
 * things you cannot read, and the only way to find one would be to hover every slot. Five readable
 * names and a distance beat ten truncated ones; paging is needed either way, because the old menu
 * simply stopped at whatever the window held and said nothing about the rest.
 *
 * <h2>The geometry, and where it is decided</h2>
 * Everything below is derived from {@link SlotGeometry} and {@link #INSET}, which is the same
 * arrangement {@code TravelPanel} has and for the same reason: the pack's generator
 * ({@code resource-pack/tools/generate_gui_rows.py}) draws the pill and the buttons from those
 * numbers, and {@code NavigatePanelTest} reads the PNGs and the row fonts back and asserts the two
 * sides still agree. Nothing here restates a pixel the pack decides.
 */
public final class NavigatePanel {

    /** The window is always six rows, whether there is one destination or forty. */
    public static final int ROWS = MenuTitle.MAX_ROWS;

    /** Destinations per page: the five rows above the control row. */
    public static final int ENTRIES_PER_PAGE = ROWS - 1;

    /** The chest row the controls sit on. */
    public static final int CONTROL_ROW = ROWS - 1;

    /** Pixels between a row's furniture and the slot cells that make it clickable. */
    public static final int INSET = 2;

    /** A full-width row plate: the slot area, inset on both sides. */
    public static final int PILL_X = SlotGeometry.ORIGIN_X + INSET;
    public static final int PILL_WIDTH = SlotGeometry.COLUMNS * SlotGeometry.PITCH - 2 * INSET;

    /** The kind icon, three pixels inside the pill. */
    private static final int ICON_X = PILL_X + 3;
    private static final int ICON_SIZE = 8;

    /** The name starts one pixel past the icon's box, which is where a button's label starts too. */
    private static final int NAME_X = ICON_X + ICON_SIZE + 5;

    /** Where the distance's right edge lands - x 164, the artifact's own number. */
    private static final int DISTANCE_RIGHT = PILL_X + PILL_WIDTH - 3;

    /** How much clear space is kept between a name and the distance beside it. */
    private static final int NAME_GAP = 4;

    // --- the control row ----------------------------------------------------------------
    private static final int STOP_X = PILL_X;
    private static final int STOP_WIDTH = 52;
    private static final int STOP_ICON_X = STOP_X + 3;
    private static final int STOP_LABEL_X = STOP_ICON_X + ICON_SIZE + 3;

    private static final int BUTTON_WIDTH = SlotGeometry.PITCH - 2 * INSET;
    private static final int PREV_X = SlotGeometry.x(6) + INSET;
    private static final int PAGE_X = SlotGeometry.x(7);
    private static final int NEXT_X = SlotGeometry.x(8) + INSET;

    // --- the slots underneath ------------------------------------------------------------
    /** The three cells the stop plate covers; each carries the same tooltip and the same click. */
    public static final List<Integer> STOP_SLOTS = List.of(
            SlotGeometry.slot(0, CONTROL_ROW), SlotGeometry.slot(1, CONTROL_ROW),
            SlotGeometry.slot(2, CONTROL_ROW));

    public static final int PREV_SLOT = SlotGeometry.slot(6, CONTROL_ROW);
    public static final int PAGE_SLOT = SlotGeometry.slot(7, CONTROL_ROW);
    public static final int NEXT_SLOT = SlotGeometry.slot(8, CONTROL_ROW);

    private NavigatePanel() {
    }

    /** The three kinds of destination, and the pictogram each is drawn with. */
    public static String icon(final NavigationTarget.Kind kind) {
        return switch (kind) {
            case WORLD_SPAWN -> Glyphs.GUI_ROW_ICON_SPAWN;
            case LAST_DEATH -> Glyphs.GUI_ROW_ICON_DEATH;
            case POI -> Glyphs.GUI_ROW_ICON_POI;
        };
    }

    /**
     * One drawn destination.
     *
     * @param icon     one of {@code Glyphs.GUI_ROW_ICON_*}
     * @param name     the destination's name, folded and shortened here
     * @param distance what stands at the right edge - a distance, or "another world"
     * @param active   whether this is the destination the player is currently being pointed at
     */
    public record Entry(String icon, String name, String distance, boolean active) {
    }

    /**
     * The whole surface, as the inventory title.
     *
     * @param title    the readable window title, already translated
     * @param entries  up to {@link #ENTRIES_PER_PAGE} destinations, top row first
     * @param stop     the stop button's label, short enough for 52 pixels
     * @param page     what stands between the two page buttons, e.g. {@code 2/3}
     * @param hasPrev  whether there is a page before this one
     * @param hasNext  whether there is a page after this one
     */
    public static Component title(final Component title, final List<Entry> entries,
                                  final String stop, final String page,
                                  final boolean hasPrev, final boolean hasNext) {
        if (entries.size() > ENTRIES_PER_PAGE) {
            throw new IllegalArgumentException(
                    "a page holds " + ENTRIES_PER_PAGE + " destinations, not " + entries.size());
        }
        final MenuTitle.Canvas canvas = MenuTitle.onPlain(ROWS);

        for (int row = 0; row < entries.size(); row++) {
            final Entry entry = entries.get(row);
            // Order matters and is draw order: the plate first, then everything that sits on it.
            // Right up to 2026-09-09 a canvas sorted its overlays right-to-left, which would have
            // painted this pill over its own label.
            canvas.rowArt(Glyphs.GUI_ROW_PILL, row, PILL_X, null);
            canvas.rowArt(entry.icon(), row, ICON_X, MenuPalette.INK);

            final String distance = MenuFont.fold(entry.distance());
            final int distanceWidth = MenuFont.width(distance);
            canvas.rowText(MenuFont.fit(entry.name(),
                            DISTANCE_RIGHT - distanceWidth - NAME_GAP - NAME_X),
                    row, NAME_X, MenuPalette.INK);
            if (distanceWidth > 0) {
                canvas.rowTextRight(distance, row, DISTANCE_RIGHT, MenuPalette.SOFT);
            }

            // Last, so the frame is the one thing nothing is drawn over: it is two pixels of white
            // on the pill's own edge and a label drawn after it would cross it.
            if (entry.active()) {
                canvas.rowArt(Glyphs.GUI_ROW_FRAME, row, PILL_X, null);
            }
        }

        canvas.rowArt(Glyphs.GUI_ROW_BUTTON_WIDE, CONTROL_ROW, STOP_X, null);
        canvas.rowArt(Glyphs.GUI_ROW_ICON_STOP, CONTROL_ROW, STOP_ICON_X, MenuPalette.ON_PLATE);
        canvas.rowText(MenuFont.fit(stop, STOP_X + STOP_WIDTH - 3 - STOP_LABEL_X),
                CONTROL_ROW, STOP_LABEL_X, MenuPalette.ON_PLATE);

        pageButton(canvas, PREV_X, Glyphs.GUI_ROW_ICON_PREV, hasPrev);
        final String label = MenuFont.fold(page);
        canvas.rowText(label, CONTROL_ROW,
                PAGE_X + (SlotGeometry.PITCH - MenuFont.width(label)) / 2, MenuPalette.SOFT);
        pageButton(canvas, NEXT_X, Glyphs.GUI_ROW_ICON_NEXT, hasNext);

        return canvas.build(title);
    }

    /**
     * One page button, drawn greyed when there is no page on that side.
     *
     * <p>Drawn greyed rather than left off: a control that vanishes moves nothing, but a player who
     * saw it a second ago now has to work out whether it was ever there. The click on a greyed one
     * is refused with the refusal sound, which is what says "this is a button and it is not for you
     * right now".</p>
     */
    private static void pageButton(final MenuTitle.Canvas canvas, final int x,
                                   final String arrow, final boolean enabled) {
        canvas.rowArt(enabled ? Glyphs.GUI_ROW_BUTTON_SMALL : Glyphs.GUI_ROW_BUTTON_SMALL_OFF,
                CONTROL_ROW, x, null);
        canvas.rowArt(arrow, CONTROL_ROW, x + (BUTTON_WIDTH - ICON_SIZE) / 2,
                enabled ? MenuPalette.INK : MenuPalette.DISABLED);
    }
}
