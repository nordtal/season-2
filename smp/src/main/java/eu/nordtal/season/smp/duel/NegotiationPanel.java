package eu.nordtal.season.smp.duel;

import eu.nordtal.season.packrendering.Glyphs;
import eu.nordtal.season.packrendering.menu.MenuFont;
import eu.nordtal.season.packrendering.menu.MenuTitle;
import eu.nordtal.season.packrendering.menu.SlotGeometry;
import eu.nordtal.season.papercommon.menu.MenuPalette;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.jspecify.annotations.Nullable;

/** The drawing of the negotiation window: one row per kit and a status row under them. */
final class NegotiationPanel {

    static final int ROWS = MenuTitle.MAX_ROWS;

    static final int KIT_ROWS = ROWS - 1;

    static final int STATUS_ROW = ROWS - 1;

    private static final int INSET = 2;

    private static final int PILL_X = SlotGeometry.ORIGIN_X + INSET;

    private static final int PILL_WIDTH = SlotGeometry.COLUMNS * SlotGeometry.PITCH - 2 * INSET;

    /** Column 0 holds the kit's icon item, so the name starts in column 1. */
    private static final int NAME_X = SlotGeometry.x(1) + INSET;

    private static final int RIGHT = PILL_X + PILL_WIDTH - 3;

    private static final int GAP = 4;

    private static final char FULL = '█';

    private static final char EMPTY = '░';

    private NegotiationPanel() {}

    /**
     * One kit row.
     *
     * @param opponent the opponent's name when they picked this kit, else null
     * @param mine     whether the viewer picked this kit
     */
    record Row(String kit, @Nullable String opponent, boolean mine) {}

    /** Returns the window as an inventory title. {@code bar} is blank unless the countdown runs. */
    static Component title(final Component title, final List<Row> rows, final String status, final String bar) {
        if (rows.size() > KIT_ROWS) {
            throw new IllegalArgumentException("the window shows " + KIT_ROWS + " kits, not " + rows.size());
        }
        final MenuTitle.Canvas canvas = MenuTitle.onPlain(ROWS);
        for (int row = 0; row < rows.size(); row++) {
            final Row kit = rows.get(row);
            canvas.rowArt(Glyphs.GUI_ROW_PILL, row, PILL_X, null);
            final String opponent = kit.opponent() == null ? "" : MenuFont.fold(kit.opponent());
            final int opponentWidth = MenuFont.width(opponent);
            canvas.rowText(MenuFont.fit(kit.kit(), RIGHT - opponentWidth - GAP - NAME_X), row, NAME_X, MenuPalette.INK);
            if (opponentWidth > 0) {
                canvas.rowTextRight(opponent, row, RIGHT, MenuPalette.SOFT);
            }
            // The frame of the viewer's own pick is drawn last.
            if (kit.mine()) {
                canvas.rowArt(Glyphs.GUI_ROW_FRAME, row, PILL_X, null);
            }
        }
        final String folded = MenuFont.fold(bar);
        final int barWidth = MenuFont.width(folded);
        final String line = MenuFont.fit(status, PILL_WIDTH - (barWidth > 0 ? barWidth + GAP : 0));
        final int lineWidth = MenuFont.width(line) + (barWidth > 0 ? GAP + barWidth : 0);
        final int left = PILL_X + (PILL_WIDTH - lineWidth) / 2;
        canvas.rowText(line, STATUS_ROW, left, MenuPalette.INK);
        if (barWidth > 0) {
            canvas.rowText(folded, STATUS_ROW, left + lineWidth - barWidth, MenuPalette.PROGRESS);
        }
        return canvas.build(title);
    }

    /** Returns a bar of {@code steps} cells, of which the first {@code done} are full. */
    static String bar(final int done, final int steps) {
        final int full = Math.clamp(done, 0, steps);
        return String.valueOf(FULL).repeat(full) + String.valueOf(EMPTY).repeat(steps - full);
    }
}
