package eu.nordtal.season.smp.npc;

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

/** Draws the deposit screen: three rows of slots, then the sample in cell 0 and the hand-in plate over cells 5 to 8. */
public final class HandInPanel {

    public static final int ROWS = 4;

    public static final int DEPOSIT_ROWS = ROWS - 1;

    public static final int DEPOSIT_SLOTS = DEPOSIT_ROWS * SlotGeometry.COLUMNS;

    public static final int FOOTER_ROW = ROWS - 1;

    /** The slot that shows a sample item of the wanted material. A player cannot take it. */
    public static final int SAMPLE_SLOT = SlotGeometry.slot(0, FOOTER_ROW);

    /** The first cell of the hand-in plate. */
    public static final int CONFIRM_COLUMN = 5;

    private static final int CONFIRM_CELLS = SlotGeometry.COLUMNS - CONFIRM_COLUMN;

    /** The slots under the hand-in plate. All of them take the same click. */
    public static final List<Integer> CONFIRM_SLOTS = confirmSlots();

    private HandInPanel() {}

    public static boolean isDeposit(final int slot) {
        return slot >= 0 && slot < DEPOSIT_SLOTS;
    }

    /**
     * Draws the screen into the inventory title.
     *
     * @param name the screen's name
     * @param mark the header's mark: what is still needed, that nothing counts, or that the objective is finished
     * @param button the label of the hand-in plate
     * @param finished whether the plate is off, because the objective is finished
     */
    public static Component title(final String name, final String mark, final String button, final boolean finished) {
        final ChestTitle title =
                ChestTitle.of(ROWS).header(Symbol.HANDIN, name, List.of(Mark.text(mark, Colours.SOFT)));
        for (int row = 0; row < DEPOSIT_ROWS; row++) {
            for (int column = 0; column < SlotGeometry.COLUMNS; column++) {
                title.slot(column, row);
            }
        }
        final Look look = finished ? Look.DISABLED : Look.PRIMARY;
        title.plate(CONFIRM_COLUMN, FOOTER_ROW, CONFIRM_CELLS, look)
                .label(CONFIRM_COLUMN, FOOTER_ROW, CONFIRM_CELLS, null, button, look);
        return title.build(BodyLine.silent(name + " " + mark));
    }

    private static List<Integer> confirmSlots() {
        final List<Integer> slots = new ArrayList<>(CONFIRM_CELLS);
        for (int column = CONFIRM_COLUMN; column < SlotGeometry.COLUMNS; column++) {
            slots.add(SlotGeometry.slot(column, FOOTER_ROW));
        }
        return List.copyOf(slots);
    }
}
