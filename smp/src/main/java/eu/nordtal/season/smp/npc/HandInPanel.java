package eu.nordtal.season.smp.npc;

import eu.nordtal.season.packrendering.Glyphs;
import eu.nordtal.season.papercommon.menu.MenuFont;
import eu.nordtal.season.papercommon.menu.MenuPalette;
import eu.nordtal.season.papercommon.menu.MenuTitle;
import eu.nordtal.season.papercommon.menu.SlotGeometry;
import java.util.List;
import net.kyori.adventure.text.Component;

/** Draws the deposit screen: one tray over the deposit slots and one confirm button. */
public final class HandInPanel {

    public static final int ROWS = 4;

    public static final int DEPOSIT_ROWS = ROWS - 1;

    public static final int DEPOSIT_SLOTS = DEPOSIT_ROWS * SlotGeometry.COLUMNS;

    public static final int FOOTER_ROW = ROWS - 1;

    /** The distance in pixels between a drawn element and the slot cells it covers. */
    public static final int INSET = 2;

    /** The tray starts at the corner of the first slot cell and has no inset. */
    public static final int TRAY_X = SlotGeometry.ORIGIN_X;

    public static final int TRAY_WIDTH = SlotGeometry.COLUMNS * SlotGeometry.PITCH;
    public static final int TRAY_HEIGHT = DEPOSIT_ROWS * SlotGeometry.PITCH;

    /** The slot that shows a sample item of the wanted material. A player cannot take it. */
    public static final int SAMPLE_SLOT = SlotGeometry.slot(0, FOOTER_ROW);

    /** The x position of the needed amount. It equals the x of the experience line in {@code GravePanel}. */
    private static final int NEEDED_X = SlotGeometry.x(1) + INSET;

    public static final int CONFIRM_X = SlotGeometry.x(6) + INSET;
    public static final int CONFIRM_WIDTH = 3 * SlotGeometry.PITCH - 2 * INSET;

    /** The three slots under the confirm button. All three have the same tooltip and the same click. */
    public static final List<Integer> CONFIRM_SLOTS = List.of(
            SlotGeometry.slot(6, FOOTER_ROW), SlotGeometry.slot(7, FOOTER_ROW), SlotGeometry.slot(8, FOOTER_ROW));

    private HandInPanel() {}

    public static boolean isDeposit(final int slot) {
        return slot >= 0 && slot < DEPOSIT_SLOTS;
    }

    /**
     * Draws the screen into the inventory title.
     *
     * @param title  the translated window title
     * @param needed the text beside the sample, for example {@code 808 left}
     * @param button the label of the confirm button, shortened to fit its plate
     */
    public static Component title(final Component title, final String needed, final String button) {
        final MenuTitle.Canvas canvas = MenuTitle.onPlain(ROWS);

        canvas.overlay(Glyphs.GUI_HANDIN_TRAY, TRAY_X, TRAY_WIDTH);
        canvas.rowText(MenuFont.fit(needed, CONFIRM_X - 4 - NEEDED_X), FOOTER_ROW, NEEDED_X, MenuPalette.INK);

        canvas.rowArt(Glyphs.GUI_ROW_BUTTON_CONFIRM, FOOTER_ROW, CONFIRM_X, null);
        // The label is centred on the plate.
        final String label = MenuFont.fit(button, CONFIRM_WIDTH - 6);
        canvas.rowText(label, FOOTER_ROW, CONFIRM_X + (CONFIRM_WIDTH - MenuFont.width(label)) / 2, MenuPalette.INK);

        return canvas.build(title);
    }
}
