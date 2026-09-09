package eu.nordtal.s2.smp.grave;

import eu.nordtal.s2.common.Glyphs;
import eu.nordtal.s2.common.menu.MenuFont;
import eu.nordtal.s2.common.menu.MenuPalette;
import eu.nordtal.s2.common.menu.MenuTitle;
import eu.nordtal.s2.common.menu.SlotGeometry;

import net.kyori.adventure.text.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws a grave: a slab of recesses holding what somebody left, and a footer that empties it.
 *
 * <p>A stone slab with <b>a recess per slot</b>, as many rows as the grave has stacks; the row
 * under it carries the dead player's own head, the experience waiting to be claimed, and a "take
 * everything" button.
 *
 * <p>The recess per slot is deliberate and differs from the hand-in tray: a grave is an inventory
 * you <em>take out of</em>, and the separate cells say these are distinct stacks any one of which
 * may be taken. The hand-in tray is a thing you throw into, where the cell means nothing.
 *
 * <p>The footer row is what makes the experience visible - it is otherwise credited silently on the
 * last item leaving - and what "take everything" needs a slot for. It costs one row, so a grave of
 * exactly 45 stacks would not fit; a player carries at most forty-one, which
 * {@link #MAX_CONTENT_ROWS} asserts.
 *
 * <p><b>Every footer slot must hold something.</b> A shift-click from the player's own inventory
 * goes into the first free slot of the window, so an empty footer cell would swallow the item
 * outside the content slots {@code Graves} writes back - lost on close, with nothing failing.
 */
public final class GravePanel {

    /** The most content rows there can be: a player carries at most 41 stacks. */
    public static final int MAX_CONTENT_ROWS = MenuTitle.MAX_ROWS - 1;

    /** Pixels between a piece of furniture and the slot cells that make it clickable. */
    public static final int INSET = 2;

    /** The slab is drawn from the slot cell's own corner, because it <em>is</em> the cells. */
    public static final int SLAB_X = SlotGeometry.ORIGIN_X;
    public static final int SLAB_WIDTH = SlotGeometry.COLUMNS * SlotGeometry.PITCH;

    /** Where the experience line starts: past the head's own cell. */
    private static final int EXPERIENCE_X = SlotGeometry.x(1) + INSET;

    public static final int TAKE_X = SlotGeometry.x(5) + INSET;
    public static final int TAKE_WIDTH = 4 * SlotGeometry.PITCH - 2 * INSET;

    private GravePanel() {
    }

    /**
     * How many rows of recesses a grave with {@code stacks} stacks needs.
     *
     * <p>At least one, so an experience-only grave - a death that dropped nothing but levels - is a
     * window rather than a footer floating on its own.
     */
    public static int contentRows(final int stacks) {
        final int rows = (Math.max(0, stacks) + SlotGeometry.COLUMNS - 1) / SlotGeometry.COLUMNS;
        return Math.max(1, Math.min(MAX_CONTENT_ROWS, rows));
    }

    /** The window's height in rows: the content, plus the footer. */
    public static int rows(final int contentRows) {
        return contentRows + 1;
    }

    /** The footer's chest row, which is the first row after the content. */
    public static int footerRow(final int contentRows) {
        return contentRows;
    }

    /** How many slots hold what the dead player was carrying. */
    public static int contentSlots(final int contentRows) {
        return contentRows * SlotGeometry.COLUMNS;
    }

    /** Whether a slot holds an item anybody may take. */
    public static boolean isContent(final int slot, final int contentRows) {
        return slot >= 0 && slot < contentSlots(contentRows);
    }

    /** The slot the dead player's own head sits in. */
    public static int headSlot(final int contentRows) {
        return SlotGeometry.slot(0, footerRow(contentRows));
    }

    /** The four cells the experience line is written across - hoverable, and never free. */
    public static List<Integer> experienceSlots(final int contentRows) {
        final List<Integer> slots = new ArrayList<>(4);
        for (int column = 1; column <= 4; column++) {
            slots.add(SlotGeometry.slot(column, footerRow(contentRows)));
        }
        return List.copyOf(slots);
    }

    /** The four cells the "take everything" plate covers. */
    public static List<Integer> takeAllSlots(final int contentRows) {
        final List<Integer> slots = new ArrayList<>(4);
        for (int column = 5; column <= 8; column++) {
            slots.add(SlotGeometry.slot(column, footerRow(contentRows)));
        }
        return List.copyOf(slots);
    }

    /**
     * The whole surface, as the inventory title.
     *
     * @param title       the readable window title, already translated
     * @param contentRows from {@link #contentRows(int)}
     * @param experience  what stands beside the head, e.g. {@code +120 XP}; blank draws nothing
     * @param takeAll     the button's label
     */
    public static Component title(final Component title, final int contentRows,
                                  final String experience, final String takeAll) {
        if (contentRows < 1 || contentRows > MAX_CONTENT_ROWS) {
            throw new IllegalArgumentException("a grave holds 1 to " + MAX_CONTENT_ROWS
                    + " rows of items, not " + contentRows);
        }
        final int footer = footerRow(contentRows);
        final MenuTitle.Canvas canvas = MenuTitle.onPlain(rows(contentRows));

        canvas.overlay(Glyphs.GUI_GRAVE_SLAB[contentRows - 1], SLAB_X, SLAB_WIDTH);
        canvas.rowText(MenuFont.fit(experience, TAKE_X - 4 - EXPERIENCE_X), footer, EXPERIENCE_X,
                MenuPalette.INK);

        canvas.rowArt(Glyphs.GUI_ROW_BUTTON_TAKE, footer, TAKE_X, null);
        final String label = MenuFont.fit(takeAll, TAKE_WIDTH - 6);
        canvas.rowText(label, footer, TAKE_X + (TAKE_WIDTH - MenuFont.width(label)) / 2,
                MenuPalette.INK);

        return canvas.build(title);
    }
}
