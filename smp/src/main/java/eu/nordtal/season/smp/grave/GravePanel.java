package eu.nordtal.season.smp.grave;

import eu.nordtal.season.packrendering.Glyphs;
import eu.nordtal.season.packrendering.menu.MenuFont;
import eu.nordtal.season.packrendering.menu.MenuTitle;
import eu.nordtal.season.packrendering.menu.SlotGeometry;
import eu.nordtal.season.papercommon.menu.MenuPalette;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;

/**
 * Draws the grave window.
 *
 * Every footer slot must hold an item. {@link Graves} reads only the content slots.
 */
public final class GravePanel {

    /** The maximum number of content rows. A player holds at most 41 stacks. */
    public static final int MAX_CONTENT_ROWS = MenuTitle.MAX_ROWS - 1;

    /** The distance in pixels between a drawn element and the slot cells it covers. */
    public static final int INSET = 2;

    /** The slab starts at the corner of the first slot cell and has no inset. */
    public static final int SLAB_X = SlotGeometry.ORIGIN_X;

    public static final int SLAB_WIDTH = SlotGeometry.COLUMNS * SlotGeometry.PITCH;

    private static final int EXPERIENCE_X = SlotGeometry.x(1) + INSET;

    public static final int TAKE_X = SlotGeometry.x(5) + INSET;
    public static final int TAKE_WIDTH = 4 * SlotGeometry.PITCH - 2 * INSET;

    private GravePanel() {}

    /** Returns the number of content rows for {@code stacks} stacks, from 1 to {@link #MAX_CONTENT_ROWS}. */
    public static int contentRows(final int stacks) {
        final int rows = (Math.max(0, stacks) + SlotGeometry.COLUMNS - 1) / SlotGeometry.COLUMNS;
        return Math.max(1, Math.min(MAX_CONTENT_ROWS, rows));
    }

    public static int rows(final int contentRows) {
        return contentRows + 1;
    }

    public static int footerRow(final int contentRows) {
        return contentRows;
    }

    public static int contentSlots(final int contentRows) {
        return contentRows * SlotGeometry.COLUMNS;
    }

    public static boolean isContent(final int slot, final int contentRows) {
        return slot >= 0 && slot < contentSlots(contentRows);
    }

    public static int headSlot(final int contentRows) {
        return SlotGeometry.slot(0, footerRow(contentRows));
    }

    public static List<Integer> experienceSlots(final int contentRows) {
        final List<Integer> slots = new ArrayList<>(4);
        for (int column = 1; column <= 4; column++) {
            slots.add(SlotGeometry.slot(column, footerRow(contentRows)));
        }
        return List.copyOf(slots);
    }

    public static List<Integer> takeAllSlots(final int contentRows) {
        final List<Integer> slots = new ArrayList<>(4);
        for (int column = 5; column <= 8; column++) {
            slots.add(SlotGeometry.slot(column, footerRow(contentRows)));
        }
        return List.copyOf(slots);
    }

    /**
     * Draws the window into the inventory title.
     *
     * @param title       the translated window title
     * @param contentRows the value of {@link #contentRows(int)}
     * @param experience  the text beside the head, for example {@code +120 XP}. A blank text draws nothing.
     * @param takeAll     the label of the take-all button
     */
    public static Component title(
            final Component title, final int contentRows, final String experience, final String takeAll) {
        if (contentRows < 1 || contentRows > MAX_CONTENT_ROWS) {
            throw new IllegalArgumentException(
                    "a grave holds 1 to " + MAX_CONTENT_ROWS + " rows of items, not " + contentRows);
        }
        final int footer = footerRow(contentRows);
        final MenuTitle.Canvas canvas = MenuTitle.onPlain(rows(contentRows));

        canvas.overlay(Glyphs.GUI_GRAVE_SLAB.get(contentRows - 1), SLAB_X, SLAB_WIDTH);
        canvas.rowText(MenuFont.fit(experience, TAKE_X - 4 - EXPERIENCE_X), footer, EXPERIENCE_X, MenuPalette.INK);

        canvas.rowArt(Glyphs.GUI_ROW_BUTTON_TAKE, footer, TAKE_X, null);
        final String label = MenuFont.fit(takeAll, TAKE_WIDTH - 6);
        canvas.rowText(label, footer, TAKE_X + (TAKE_WIDTH - MenuFont.width(label)) / 2, MenuPalette.INK);

        return canvas.build(title);
    }
}
