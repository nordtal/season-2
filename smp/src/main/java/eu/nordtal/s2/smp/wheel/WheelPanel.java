package eu.nordtal.s2.smp.wheel;

import eu.nordtal.s2.common.Glyphs;
import eu.nordtal.s2.common.menu.MenuFont;
import eu.nordtal.s2.common.menu.MenuPalette;
import eu.nordtal.s2.common.menu.MenuTitle;
import eu.nordtal.s2.common.menu.SlotGeometry;

import net.kyori.adventure.text.Component;

import java.util.List;

/**
 * Draws the wheel: twelve prizes in a ring round a hub, and the controls beside it.
 *
 * <h2>What the picture is</h2>
 * Design {@code W3} - the ring, twelve cells - <b>moved two slot columns to the left</b> (owner,
 * 2026-09-08), so the four columns it frees carry the "again" button and the two things a player
 * needs to know: how many spins they have left, and what earns another one. On a nine-by-five grid
 * a circle is a rounded square: three cells along the top, three down each side, three along the
 * bottom, and the corners stay frame.
 *
 * <h2>The pointer became a frame, and that is a consequence of the move</h2>
 * {@code W3} marks the resting cell with a triangle above it at y 13-16 - which is <em>inside the
 * title bar</em>, and works only because at x 85 it sits to the right of the readable title. At
 * x 49 it does not: the window's own title runs to about x 58 in both languages, so the two would
 * meet on the title's last pixel row and nothing short of a client could say by how much. The pack
 * already has a word for "this one" - the two-pixel white frame {@code travel_here} uses - so the
 * resting cell wears that instead, over the lighter backing {@code W3} gives it anyway. Both cues,
 * no collision, and nothing outside the window. <b>This is a decision that is the owner's to
 * confirm</b>; what is not open is that the triangle cannot stay where it was.
 *
 * <h2>Why the ring is a whole panel</h2>
 * Because a circle on this grid is the band <em>between</em> the cells, not something that lands on
 * any one of them - there is no row a ring belongs to. So it is a window of its own like the
 * balloon's, and everything drawn on top of it is a row glyph as usual.
 */
public final class WheelPanel {

    /** Five rows: three cells tall, with the ring's band on the rows above and below its middle. */
    public static final int ROWS = 5;

    /** Pixels between a piece of furniture and the slot cells that make it clickable. */
    public static final int INSET = 2;

    /**
     * The twelve prize cells, clockwise from the leftmost of the three along the top.
     *
     * <p>Index 0 is where the winner comes to rest, which is what {@link #shape()} tells
     * {@link WheelStrip} and what the panel's baked frame marks.
     */
    private static final int[][] CELLS = {
            {2, 0}, {3, 0}, {4, 1}, {4, 2}, {4, 3},
            {3, 4}, {2, 4}, {1, 4}, {0, 3}, {0, 2}, {0, 1}, {1, 0},
    };

    /** The slot each cell is, in the same order - what the animation writes its icons into. */
    public static final List<Integer> CELL_SLOTS = cellSlots();

    /** The cell in the middle of the ring: the hub, which carries the number and the tooltip. */
    public static final int HUB_SLOT = SlotGeometry.slot(2, 2);

    /** Where the hub's number is centred - the middle of the hub's own slot cell. */
    private static final int HUB_CENTRE_X = SlotGeometry.x(2) + SlotGeometry.PITCH / 2;
    private static final int HUB_ROW = 2;

    // --- the controls, on the four columns the ring's move freed --------------------------
    private static final int INFO_X = SlotGeometry.x(5) + INSET;
    private static final int INFO_RIGHT = SlotGeometry.x(8) + SlotGeometry.PITCH - INSET;

    /** The row each of the three lines is written on. */
    private static final int SPINS_ROW = 1;
    private static final int RULE_TOP_ROW = 2;
    private static final int RULE_BOTTOM_ROW = 3;

    public static final int AGAIN_ROW = 4;
    public static final int AGAIN_X = SlotGeometry.x(6) + INSET;
    public static final int AGAIN_WIDTH = 3 * SlotGeometry.PITCH - 2 * INSET;

    /** The three cells the "again" plate covers; each carries the same tooltip and the same click. */
    public static final List<Integer> AGAIN_SLOTS = List.of(
            SlotGeometry.slot(6, AGAIN_ROW), SlotGeometry.slot(7, AGAIN_ROW),
            SlotGeometry.slot(8, AGAIN_ROW));

    /** The four cells the three text lines are written across - hoverable, and never free. */
    public static final List<Integer> INFO_SLOTS = infoSlots();

    private WheelPanel() {
    }

    /** What {@link WheelStrip} has to be built for: twelve cells, resting on the first. */
    public static WheelStrip.Shape shape() {
        return new WheelStrip.Shape(CELLS.length, 0);
    }

    /**
     * The whole surface, as the inventory title.
     *
     * @param title    the readable window title, already translated
     * @param spins    what stands in the hub - the spins left after this one, as a bare number
     * @param left     the line naming those spins in words
     * @param ruleTop  the first line of "one spin per n % share"
     * @param ruleFoot the second line of it
     * @param again    the button's label
     */
    public static Component title(final Component title, final String spins, final String left,
                                  final String ruleTop, final String ruleFoot, final String again) {
        final MenuTitle.Canvas canvas = MenuTitle.on(Glyphs.GUI_WHEEL_RING);

        final String number = MenuFont.fold(spins);
        canvas.rowText(number, HUB_ROW, HUB_CENTRE_X - MenuFont.width(number) / 2,
                MenuPalette.INK);

        canvas.rowText(MenuFont.fit(left, INFO_RIGHT - INFO_X), SPINS_ROW, INFO_X, MenuPalette.INK);
        canvas.rowText(MenuFont.fit(ruleTop, INFO_RIGHT - INFO_X), RULE_TOP_ROW, INFO_X,
                MenuPalette.SOFT);
        canvas.rowText(MenuFont.fit(ruleFoot, INFO_RIGHT - INFO_X), RULE_BOTTOM_ROW, INFO_X,
                MenuPalette.SOFT);

        canvas.rowArt(Glyphs.GUI_ROW_BUTTON_CONFIRM, AGAIN_ROW, AGAIN_X, null);
        final String label = MenuFont.fit(again, AGAIN_WIDTH - 6);
        canvas.rowText(label, AGAIN_ROW, AGAIN_X + (AGAIN_WIDTH - MenuFont.width(label)) / 2,
                MenuPalette.INK);

        return canvas.build(title);
    }

    private static List<Integer> cellSlots() {
        final java.util.List<Integer> slots = new java.util.ArrayList<>(CELLS.length);
        for (final int[] cell : CELLS) {
            slots.add(SlotGeometry.slot(cell[0], cell[1]));
        }
        return List.copyOf(slots);
    }

    /**
     * The four cells under the three lines of text.
     *
     * <p>They hold a {@code BlankItem} rather than nothing for the reason the grave's footer does:
     * a shift-click from the player's own inventory goes into the first free slot of the window, and
     * the wheel cancels every click but does not stop the move landing somewhere it is then drawn
     * over the text. Filling them also gives the two sentences a tooltip, which is where the exact
     * thresholds can be spelled out.</p>
     */
    private static List<Integer> infoSlots() {
        final java.util.List<Integer> slots = new java.util.ArrayList<>();
        for (final int row : new int[] {SPINS_ROW, RULE_TOP_ROW, RULE_BOTTOM_ROW}) {
            for (int column = 5; column <= 8; column++) {
                slots.add(SlotGeometry.slot(column, row));
            }
        }
        return List.copyOf(slots);
    }
}
