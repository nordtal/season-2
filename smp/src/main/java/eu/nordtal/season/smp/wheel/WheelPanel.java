package eu.nordtal.season.smp.wheel;

import eu.nordtal.season.packrendering.menu.SlotGeometry;
import eu.nordtal.season.packrendering.ui.BodyLine;
import eu.nordtal.season.packrendering.ui.ChestTitle;
import eu.nordtal.season.packrendering.ui.Colours;
import eu.nordtal.season.packrendering.ui.Look;
import eu.nordtal.season.packrendering.ui.Symbol;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;

/**
 * Draws the wheel on the chest panel.
 * Twelve prize cells run round an empty hub with the pointer above the winner's cell, and beside them stand the "again"
 * plate and the spins left under it.
 */
public final class WheelPanel {

    public static final int ROWS = 6;

    /**
     * The prize cells as column and row, clockwise. Index 0 is the resting cell of the winner.
     * Row 0 holds the pointer above it.
     */
    private static final int[][] CELLS = {
        {2, 1}, {3, 1}, {4, 2}, {4, 3}, {4, 4}, {3, 5}, {2, 5}, {1, 5}, {0, 4}, {0, 3}, {0, 2}, {1, 1},
    };

    public static final List<Integer> CELL_SLOTS = cellSlots();

    /** The first cell of the column right of the ring, which holds the spins line and the plate. */
    private static final int SIDE_COLUMN = 5;

    private static final int SIDE_CELLS = SlotGeometry.COLUMNS - SIDE_COLUMN;

    public static final int AGAIN_ROW = 3;

    private static final int SPINS_ROW = AGAIN_ROW + 1;

    /** The slots under the "again" plate, which each take its click. */
    public static final List<Integer> AGAIN_SLOTS = againSlots();

    private WheelPanel() {}

    public static WheelStrip.Shape shape() {
        return new WheelStrip.Shape(CELLS.length, 0);
    }

    /**
     * Returns the inventory title that draws the whole window.
     *
     * @param name the wheel's name
     * @param spins the line that says how many spins are left after this one
     * @param again the label of the plate
     * @param spinLeft whether the plate is on, because the wheel stands and a spin is left
     */
    public static Component title(final String name, final String spins, final String again, final boolean spinLeft) {
        final ChestTitle title = ChestTitle.of(ROWS).header(Symbol.WHEEL, name, List.of());
        for (final int[] cell : CELLS) {
            title.slot(cell[0], cell[1]);
        }
        final int[] winner = CELLS[0];
        title.symbol(
                ChestTitle.plateX(winner[0]) + (ChestTitle.plateWidth(1) - Symbol.ADVANCE + 1) / 2,
                winner[1] - 1,
                Symbol.POINTER,
                Colours.INK);
        title.centred(SIDE_COLUMN, SPINS_ROW, SIDE_CELLS, spins, Colours.SOFT);
        final Look look = spinLeft ? Look.PRIMARY : Look.DISABLED;
        title.plate(SIDE_COLUMN, AGAIN_ROW, SIDE_CELLS, look)
                .label(SIDE_COLUMN, AGAIN_ROW, SIDE_CELLS, null, again, look);
        return title.build(BodyLine.silent(name + " " + spins));
    }

    private static List<Integer> cellSlots() {
        final List<Integer> slots = new ArrayList<>(CELLS.length);
        for (final int[] cell : CELLS) {
            slots.add(SlotGeometry.slot(cell[0], cell[1]));
        }
        return List.copyOf(slots);
    }

    private static List<Integer> againSlots() {
        final List<Integer> slots = new ArrayList<>(SIDE_CELLS);
        for (int column = SIDE_COLUMN; column < SlotGeometry.COLUMNS; column++) {
            slots.add(SlotGeometry.slot(column, AGAIN_ROW));
        }
        return List.copyOf(slots);
    }
}
