package eu.nordtal.season.smp.wheel;

import eu.nordtal.season.packrendering.Glyphs;
import eu.nordtal.season.papercommon.menu.MenuFont;
import eu.nordtal.season.papercommon.menu.MenuPalette;
import eu.nordtal.season.papercommon.menu.MenuTitle;
import eu.nordtal.season.papercommon.menu.SlotGeometry;
import java.util.List;
import net.kyori.adventure.text.Component;

public final class WheelPanel {

    public static final int ROWS = 5;

    public static final int INSET = 2;

    /** The prize cells as column and row, clockwise. Index 0 is the resting cell of the winner. */
    private static final int[][] CELLS = {
        {2, 0}, {3, 0}, {4, 1}, {4, 2}, {4, 3}, {3, 4}, {2, 4}, {1, 4}, {0, 3}, {0, 2}, {0, 1}, {1, 0},
    };

    public static final List<Integer> CELL_SLOTS = cellSlots();

    public static final int HUB_SLOT = SlotGeometry.slot(2, 2);

    private static final int HUB_CENTRE_X = SlotGeometry.x(2) + SlotGeometry.PITCH / 2;

    private static final int HUB_ROW = 2;

    private static final int INFO_X = SlotGeometry.x(5) + INSET;
    private static final int INFO_RIGHT = SlotGeometry.x(8) + SlotGeometry.PITCH - INSET;

    private static final int SPINS_ROW = 1;

    private static final int RULE_TOP_ROW = 2;
    private static final int RULE_BOTTOM_ROW = 3;

    public static final int AGAIN_ROW = 4;
    public static final int AGAIN_X = SlotGeometry.x(6) + INSET;
    public static final int AGAIN_WIDTH = 3 * SlotGeometry.PITCH - 2 * INSET;

    public static final List<Integer> AGAIN_SLOTS =
            List.of(SlotGeometry.slot(6, AGAIN_ROW), SlotGeometry.slot(7, AGAIN_ROW), SlotGeometry.slot(8, AGAIN_ROW));

    public static final List<Integer> INFO_SLOTS = infoSlots();

    private WheelPanel() {}

    public static WheelStrip.Shape shape() {
        return new WheelStrip.Shape(CELLS.length, 0);
    }

    /** Returns the inventory title that draws the whole window. {@code spins} is the number left after this spin. */
    public static Component title(
            final Component title,
            final String spins,
            final String left,
            final String ruleTop,
            final String ruleFoot,
            final String again) {
        final MenuTitle.Canvas canvas = MenuTitle.on(Glyphs.GUI_WHEEL_RING);

        final String number = MenuFont.fold(spins);
        canvas.rowText(number, HUB_ROW, HUB_CENTRE_X - MenuFont.width(number) / 2, MenuPalette.INK);

        canvas.rowText(MenuFont.fit(left, INFO_RIGHT - INFO_X), SPINS_ROW, INFO_X, MenuPalette.INK);
        canvas.rowText(MenuFont.fit(ruleTop, INFO_RIGHT - INFO_X), RULE_TOP_ROW, INFO_X, MenuPalette.SOFT);
        canvas.rowText(MenuFont.fit(ruleFoot, INFO_RIGHT - INFO_X), RULE_BOTTOM_ROW, INFO_X, MenuPalette.SOFT);

        canvas.rowArt(Glyphs.GUI_ROW_BUTTON_CONFIRM, AGAIN_ROW, AGAIN_X, null);
        final String label = MenuFont.fit(again, AGAIN_WIDTH - 6);
        canvas.rowText(label, AGAIN_ROW, AGAIN_X + (AGAIN_WIDTH - MenuFont.width(label)) / 2, MenuPalette.INK);

        return canvas.build(title);
    }

    private static List<Integer> cellSlots() {
        final java.util.List<Integer> slots = new java.util.ArrayList<>(CELLS.length);
        for (final int[] cell : CELLS) {
            slots.add(SlotGeometry.slot(cell[0], cell[1]));
        }
        return List.copyOf(slots);
    }

    /** Each of these slots holds a {@code BlankItem}. */
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
