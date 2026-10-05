package eu.nordtal.season.smp.npc;

import eu.nordtal.season.packrendering.Glyphs;
import eu.nordtal.season.papercommon.menu.MenuFont;
import eu.nordtal.season.papercommon.menu.MenuPalette;
import eu.nordtal.season.papercommon.menu.MenuTitle;
import eu.nordtal.season.papercommon.menu.SlotGeometry;
import java.util.List;
import net.kyori.adventure.text.Component;

/**
 * Draws the deposit screen: one tray to put things into, and one button that takes them.
 *
 * One tray rather than a recess per slot, unlike the grave: the cell a player drops into means nothing here.
 */
public final class HandInPanel {

    /** Three rows to fill, and one row of controls under them. */
    public static final int ROWS = 4;

    /** The chest rows a player may put items into, which the tray covers exactly. */
    public static final int DEPOSIT_ROWS = ROWS - 1;

    public static final int DEPOSIT_SLOTS = DEPOSIT_ROWS * SlotGeometry.COLUMNS;

    /** The row the sample, the count and the button sit on. */
    public static final int FOOTER_ROW = ROWS - 1;

    /** Pixels between a piece of furniture and the slot cells that make it clickable. */
    public static final int INSET = 2;

    /** The tray is drawn from the slot cell's own corner, not inset, because it is the cells. */
    public static final int TRAY_X = SlotGeometry.ORIGIN_X;

    public static final int TRAY_WIDTH = SlotGeometry.COLUMNS * SlotGeometry.PITCH;
    public static final int TRAY_HEIGHT = DEPOSIT_ROWS * SlotGeometry.PITCH;

    /** The slot holding a real item of the wanted material: a sample, and never takeable. */
    public static final int SAMPLE_SLOT = SlotGeometry.slot(0, FOOTER_ROW);

    /** Where "808 left" starts: the same x as the grave's experience line, so the two footers match. */
    private static final int NEEDED_X = SlotGeometry.x(1) + INSET;

    public static final int CONFIRM_X = SlotGeometry.x(6) + INSET;
    public static final int CONFIRM_WIDTH = 3 * SlotGeometry.PITCH - 2 * INSET;

    /** The three cells the confirm plate covers; each carries the same tooltip and the same click. */
    public static final List<Integer> CONFIRM_SLOTS = List.of(
            SlotGeometry.slot(6, FOOTER_ROW), SlotGeometry.slot(7, FOOTER_ROW), SlotGeometry.slot(8, FOOTER_ROW));

    private HandInPanel() {}

    /** Whether a slot is one a player may put something into. */
    public static boolean isDeposit(final int slot) {
        return slot >= 0 && slot < DEPOSIT_SLOTS;
    }

    /**
     * The whole surface, as the inventory title.
     *
     * @param title  the readable window title, already translated
     * @param needed what stands beside the sample, e.g. {@code 808 left}
     * @param button the confirm button's label, short enough for its plate
     */
    public static Component title(final Component title, final String needed, final String button) {
        final MenuTitle.Canvas canvas = MenuTitle.onPlain(ROWS);

        canvas.overlay(Glyphs.GUI_HANDIN_TRAY, TRAY_X, TRAY_WIDTH);
        canvas.rowText(MenuFont.fit(needed, CONFIRM_X - 4 - NEEDED_X), FOOTER_ROW, NEEDED_X, MenuPalette.INK);

        canvas.rowArt(Glyphs.GUI_ROW_BUTTON_CONFIRM, FOOTER_ROW, CONFIRM_X, null);
        // Centred on its own plate, not left-aligned like a row's name.
        final String label = MenuFont.fit(button, CONFIRM_WIDTH - 6);
        canvas.rowText(label, FOOTER_ROW, CONFIRM_X + (CONFIRM_WIDTH - MenuFont.width(label)) / 2, MenuPalette.INK);

        return canvas.build(title);
    }
}
