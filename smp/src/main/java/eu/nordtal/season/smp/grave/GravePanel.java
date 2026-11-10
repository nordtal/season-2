package eu.nordtal.season.smp.grave;

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
import org.jspecify.annotations.Nullable;

/**
 * Draws the grave window: a slot frame on every content cell, and the action row right under them.
 * The row holds the dead player's head in cell 0 and "take all" over cells 4 to 8. {@link Graves} reads only the
 * content slots.
 */
public final class GravePanel {

    /** The maximum number of content rows. A player holds at most 41 stacks, so a grave never pages. */
    public static final int MAX_CONTENT_ROWS = ChestTitle.MAX_ROWS - 1;

    /** The first cell of the take-all plate. */
    public static final int TAKE_ALL_COLUMN = 4;

    private static final int TAKE_ALL_CELLS = SlotGeometry.COLUMNS - TAKE_ALL_COLUMN;

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

    public static List<Integer> takeAllSlots(final int contentRows) {
        final List<Integer> slots = new ArrayList<>(TAKE_ALL_CELLS);
        for (int column = TAKE_ALL_COLUMN; column < SlotGeometry.COLUMNS; column++) {
            slots.add(SlotGeometry.slot(column, footerRow(contentRows)));
        }
        return List.copyOf(slots);
    }

    /**
     * Draws the window into the inventory title.
     *
     * @param name the window's name
     * @param experience the header's mark, such as {@code +120 XP}, or null for a grave without experience
     * @param takeAll the label of the take-all plate
     */
    public static Component title(
            final String name, final int contentRows, final @Nullable String experience, final String takeAll) {
        if (contentRows < 1 || contentRows > MAX_CONTENT_ROWS) {
            throw new IllegalArgumentException(
                    "a grave holds 1 to " + MAX_CONTENT_ROWS + " rows of items, not " + contentRows);
        }
        final List<Mark> marks = experience == null ? List.of() : List.of(Mark.text(experience, Colours.SOFT));
        final ChestTitle title = ChestTitle.of(rows(contentRows)).header(Symbol.GRAVE, name, marks);
        for (int row = 0; row < contentRows; row++) {
            for (int column = 0; column < SlotGeometry.COLUMNS; column++) {
                title.slot(column, row);
            }
        }
        final int footer = footerRow(contentRows);
        title.plate(TAKE_ALL_COLUMN, footer, TAKE_ALL_CELLS, Look.PRIMARY)
                .label(TAKE_ALL_COLUMN, footer, TAKE_ALL_CELLS, null, takeAll, Look.PRIMARY);
        return title.build(BodyLine.silent(experience == null ? name : name + " " + experience));
    }
}
