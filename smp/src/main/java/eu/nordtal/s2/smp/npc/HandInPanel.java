package eu.nordtal.s2.smp.npc;

import eu.nordtal.s2.common.Glyphs;
import eu.nordtal.s2.common.menu.MenuFont;
import eu.nordtal.s2.common.menu.MenuPalette;
import eu.nordtal.s2.common.menu.MenuTitle;
import eu.nordtal.s2.common.menu.SlotGeometry;

import net.kyori.adventure.text.Component;

import java.util.List;

/**
 * Draws the deposit screen: one tray to put things into, and one button that takes them.
 *
 * <h2>What the picture is</h2>
 * Design {@code H2} (owner, 2026-09-08): <b>one large tray</b>, not a recess per slot. The three
 * chest rows a player fills are covered by a single sunken surface at the slot area's own origin,
 * and the row under it carries a sample of what is wanted, how much is still needed, and the
 * confirm button.
 *
 * <h2>Why this is a tray and the grave is not</h2>
 * <b>This difference is deliberate and it is not a tidying opportunity.</b> A tray is a thing you
 * throw into: what matters is that the whole area accepts items, and drawing nine separate cells
 * would suggest the cell you drop into means something, which it does not. A grave is an
 * <em>inventory you take out of</em>, and there the separate cells are the information - they say
 * these are distinct stacks and any one of them may be taken. So {@code GravePanel} draws a recess
 * per slot and this draws one surface, and making the two the same would lose one of the two
 * meanings whichever way it went (owner, 2026-09-08).
 *
 * <p>The cost of the tray is a ghost square: vanilla's 16 x 16 hover highlight still snaps to the
 * 18-pixel grid the surface is hiding, so moving the mouse over an empty part of the tray shows a
 * square that is not drawn anywhere. That was the owner's call with the drawing in front of them.
 *
 * <h2>What is not here</h2>
 * The artifact's {@code H2} also draws a second button - "collect", which pulls matching items out
 * of the player's own inventory into the tray. That is question <b>E4</b> in the artifact and it is
 * unanswered: it is a new action rather than a new surface, and this pass repaints windows. The
 * space it would occupy is left empty rather than filled with something else, so adding it later is
 * a plate and a slot map and nothing else.
 */
public final class HandInPanel {

    /** Three rows to fill, and one row of controls under them. */
    public static final int ROWS = 4;

    /** The chest rows a player may put items into - the tray covers exactly these. */
    public static final int DEPOSIT_ROWS = ROWS - 1;

    /** How many slots that is. */
    public static final int DEPOSIT_SLOTS = DEPOSIT_ROWS * SlotGeometry.COLUMNS;

    /** The row the sample, the count and the button sit on. */
    public static final int FOOTER_ROW = ROWS - 1;

    /** Pixels between a piece of furniture and the slot cells that make it clickable. */
    public static final int INSET = 2;

    // --- the tray -------------------------------------------------------------------------
    /**
     * The tray is drawn from the slot <em>cell's</em> own corner and not inset, because it is the
     * cells: a tray inset two would leave a two-pixel margin of panel around an area whose whole
     * claim is that it is one surface.
     */
    public static final int TRAY_X = SlotGeometry.ORIGIN_X;
    public static final int TRAY_WIDTH = SlotGeometry.COLUMNS * SlotGeometry.PITCH;
    public static final int TRAY_HEIGHT = DEPOSIT_ROWS * SlotGeometry.PITCH;

    // --- the footer -----------------------------------------------------------------------
    /** The slot holding a real item of the wanted material - a sample, and never takeable. */
    public static final int SAMPLE_SLOT = SlotGeometry.slot(0, FOOTER_ROW);

    /**
     * Where "808 left" starts: two pixels into the cell after the sample's.
     *
     * <p>The same x the grave's experience line uses, and that is the point - these two windows are
     * the same footer with a different sentence on it, and a label starting eight pixels further
     * right in one of them is the kind of difference nobody can name but everybody sees.</p>
     */
    private static final int NEEDED_X = SlotGeometry.x(1) + INSET;

    public static final int CONFIRM_X = SlotGeometry.x(6) + INSET;
    public static final int CONFIRM_WIDTH = 3 * SlotGeometry.PITCH - 2 * INSET;

    /** The three cells the confirm plate covers; each carries the same tooltip and the same click. */
    public static final List<Integer> CONFIRM_SLOTS = List.of(
            SlotGeometry.slot(6, FOOTER_ROW), SlotGeometry.slot(7, FOOTER_ROW),
            SlotGeometry.slot(8, FOOTER_ROW));

    private HandInPanel() {
    }

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
        canvas.rowText(MenuFont.fit(needed, CONFIRM_X - 4 - NEEDED_X), FOOTER_ROW, NEEDED_X,
                MenuPalette.INK);

        canvas.rowArt(Glyphs.GUI_ROW_BUTTON_CONFIRM, FOOTER_ROW, CONFIRM_X, null);
        // Centred on its own plate rather than left-aligned like a row entry's name: this is a
        // button and its label is its whole content, so anything else reads as a caption beside it.
        final String label = MenuFont.fit(button, CONFIRM_WIDTH - 6);
        canvas.rowText(label, FOOTER_ROW,
                CONFIRM_X + (CONFIRM_WIDTH - MenuFont.width(label)) / 2, MenuPalette.INK);

        return canvas.build(title);
    }
}
