package eu.nordtal.season.smp.wheel;

import eu.nordtal.season.packrendering.Glyphs;
import eu.nordtal.season.packrendering.menu.MenuFont;
import eu.nordtal.season.packrendering.menu.MenuTitle;
import eu.nordtal.season.packrendering.menu.SlotGeometry;
import eu.nordtal.season.papercommon.menu.MenuPalette;
import java.util.List;
import java.util.stream.Stream;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;

public final class WheelPanel {

    public static final int ROWS = 6;

    public static final int INSET = 2;

    /**
     * The prize cells as column and row, clockwise. Index 0 is the resting cell of the winner.
     * Row 0 is left free for the pointer, which would otherwise cut the separator under the title.
     */
    private static final int[][] CELLS = {
        {2, 1}, {3, 1}, {4, 2}, {4, 3}, {4, 4}, {3, 5}, {2, 5}, {1, 5}, {0, 4}, {0, 3}, {0, 2}, {1, 1},
    };

    public static final List<Integer> CELL_SLOTS = cellSlots();

    public static final int HUB_SLOT = SlotGeometry.slot(2, 3);

    private static final int HUB_CENTRE_X = SlotGeometry.x(2) + SlotGeometry.PITCH / 2;

    private static final int HUB_ROW = 3;

    private static final int INFO_X = SlotGeometry.x(5) + INSET;
    private static final int INFO_RIGHT = SlotGeometry.x(8) + SlotGeometry.PITCH - INSET;

    private static final int SPINS_ROW = 2;

    private static final int RULE_TOP_ROW = 3;
    private static final int RULE_BOTTOM_ROW = 4;

    public static final int AGAIN_ROW = 5;
    /** The width of the {@code GUI_ROW_BUTTON_CONFIRM} plate. */
    public static final int AGAIN_WIDTH = 3 * SlotGeometry.PITCH - 2 * INSET;

    public static final int AGAIN_X = SlotGeometry.x(5) + (4 * SlotGeometry.PITCH - AGAIN_WIDTH) / 2;

    /** The centred plate overlaps all four columns beside the ring, so each of them takes its click. */
    public static final List<Integer> AGAIN_SLOTS = List.of(
            SlotGeometry.slot(5, AGAIN_ROW),
            SlotGeometry.slot(6, AGAIN_ROW),
            SlotGeometry.slot(7, AGAIN_ROW),
            SlotGeometry.slot(8, AGAIN_ROW));

    public static final List<Integer> INFO_SLOTS = infoSlots();

    /** The slots that hold the hub's item: the hub and every info slot. */
    public static final List<Integer> HUB_ITEM_SLOTS =
            Stream.concat(Stream.of(HUB_SLOT), INFO_SLOTS.stream()).toList();

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

        info(canvas, left, SPINS_ROW, MenuPalette.INK);
        info(canvas, ruleTop, RULE_TOP_ROW, MenuPalette.SOFT);
        info(canvas, ruleFoot, RULE_BOTTOM_ROW, MenuPalette.SOFT);

        canvas.rowArt(Glyphs.GUI_ROW_BUTTON_CONFIRM, AGAIN_ROW, AGAIN_X, null);
        final String label = MenuFont.fit(again, AGAIN_WIDTH - 6);
        canvas.rowText(label, AGAIN_ROW, AGAIN_X + (AGAIN_WIDTH - MenuFont.width(label)) / 2, MenuPalette.INK);

        return canvas.build(title);
    }

    /** Draws one line beside the ring, centred between {@link #INFO_X} and {@link #INFO_RIGHT}. */
    private static void info(final MenuTitle.Canvas canvas, final String text, final int row, final TextColor colour) {
        final String line = MenuFont.fit(text, INFO_RIGHT - INFO_X);
        canvas.rowText(line, row, INFO_X + (INFO_RIGHT - INFO_X - MenuFont.width(line)) / 2, colour);
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
