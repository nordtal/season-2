package eu.nordtal.season.smp.npc;

import eu.nordtal.season.packrendering.menu.SlotGeometry;
import eu.nordtal.season.packrendering.ui.BodyLine;
import eu.nordtal.season.packrendering.ui.ChestTitle;
import eu.nordtal.season.packrendering.ui.Colours;
import eu.nordtal.season.packrendering.ui.Look;
import eu.nordtal.season.packrendering.ui.Mark;
import eu.nordtal.season.packrendering.ui.Symbol;
import java.util.List;
import java.util.Set;
import net.kyori.adventure.text.Component;

/**
 * Draws the three-row deposit chest with five explicit deposit slots and live figures outside the title.
 * Deposit slots sit in the middle of row 0, live figures in rows 0 and 1, and the hand-in plate over cells 2 to 6.
 */
public final class HandInPanel {

    public static final int ROWS = 3;

    public static final int DEPOSIT_ROW = 0;

    public static final int BAR_ROW = 1;

    public static final int FOOTER_ROW = 2;

    /** The explicit set of five deposit slots in row 0, columns 2 to 6. */
    public static final List<Integer> DEPOSIT_SLOTS = List.of(
            SlotGeometry.slot(2, DEPOSIT_ROW),
            SlotGeometry.slot(3, DEPOSIT_ROW),
            SlotGeometry.slot(4, DEPOSIT_ROW),
            SlotGeometry.slot(5, DEPOSIT_ROW),
            SlotGeometry.slot(6, DEPOSIT_ROW));

    private static final Set<Integer> DEPOSIT_SET = Set.copyOf(DEPOSIT_SLOTS);

    /** The slots left of the deposit slots that hold the live accepted count and wanted items tooltip. */
    public static final List<Integer> TICK_SLOTS =
            List.of(SlotGeometry.slot(0, DEPOSIT_ROW), SlotGeometry.slot(1, DEPOSIT_ROW));

    /** The slots right of the deposit slots that hold the live return count and tooltip. */
    public static final List<Integer> RETURN_SLOTS =
            List.of(SlotGeometry.slot(7, DEPOSIT_ROW), SlotGeometry.slot(8, DEPOSIT_ROW));

    /** The nine slots of row 1 that hold the live progress bar figures outside the title. */
    public static final List<Integer> BAR_SLOTS = List.of(
            SlotGeometry.slot(0, BAR_ROW),
            SlotGeometry.slot(1, BAR_ROW),
            SlotGeometry.slot(2, BAR_ROW),
            SlotGeometry.slot(3, BAR_ROW),
            SlotGeometry.slot(4, BAR_ROW),
            SlotGeometry.slot(5, BAR_ROW),
            SlotGeometry.slot(6, BAR_ROW),
            SlotGeometry.slot(7, BAR_ROW),
            SlotGeometry.slot(8, BAR_ROW));

    /** The first cell of the hand-in plate in row 2. */
    public static final int CONFIRM_COLUMN = 2;

    private static final int CONFIRM_CELLS = 5;

    /** The slots under the hand-in plate (row 2, columns 2 to 6). All of them take the same click. */
    public static final List<Integer> CONFIRM_SLOTS = List.of(
            SlotGeometry.slot(2, FOOTER_ROW),
            SlotGeometry.slot(3, FOOTER_ROW),
            SlotGeometry.slot(4, FOOTER_ROW),
            SlotGeometry.slot(5, FOOTER_ROW),
            SlotGeometry.slot(6, FOOTER_ROW));

    private HandInPanel() {}

    /** Returns whether {@code slot} is one of the five explicit deposit slots. */
    public static boolean isDeposit(final int slot) {
        return DEPOSIT_SET.contains(slot);
    }

    /**
     * Draws the three-row deposit chest into the inventory title.
     *
     * @param name     the objective's name
     * @param mark     the header's mark: opening count, refusal, or finished
     * @param refused  whether {@code mark} is a refusal, drawn in refusal red
     * @param button   the label of the hand-in plate
     * @param finished whether the plate is off, because the objective is finished
     */
    public static Component title(
            final String name, final String mark, final boolean refused, final String button, final boolean finished) {
        final ChestTitle title = ChestTitle.of(ROWS)
                .boldHeader(Symbol.HANDIN, name, List.of(Mark.text(mark, refused ? Colours.REFUSAL : Colours.SOFT)));
        for (final int slot : DEPOSIT_SLOTS) {
            title.depositSlot(slot % SlotGeometry.COLUMNS, slot / SlotGeometry.COLUMNS);
        }
        final Look look = finished ? Look.DISABLED : Look.PRIMARY;
        title.plate(CONFIRM_COLUMN, FOOTER_ROW, CONFIRM_CELLS, look)
                .label(CONFIRM_COLUMN, FOOTER_ROW, CONFIRM_CELLS, Symbol.HANDIN, button, look);
        return title.build(BodyLine.silent(name + " " + mark));
    }
}
