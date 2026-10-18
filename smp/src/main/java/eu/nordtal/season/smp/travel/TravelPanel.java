package eu.nordtal.season.smp.travel;

import eu.nordtal.season.packrendering.Glyphs;
import eu.nordtal.season.papercommon.menu.MenuTitle;
import eu.nordtal.season.papercommon.menu.SlotGeometry;
import java.util.List;
import java.util.Optional;
import net.kyori.adventure.text.Component;

/**
 * Draws the title of {@link BalloonGui}: the travel panel and a state overlay on each card that needs one.
 * {@code TravelPanelTest} checks the panel PNG against this geometry.
 */
public final class TravelPanel {

    /** Pixels between the edge of a card and the slot area it covers. */
    public static final int INSET = 2;

    public static final int CARD_WIDTH = BalloonMenu.CARD_COLUMNS * SlotGeometry.PITCH - 2 * INSET;

    public static final int CARD_HEIGHT = BalloonMenu.CARD_ROWS * SlotGeometry.PITCH - 2 * INSET;

    private TravelPanel() {}

    public static int x(final int column) {
        return SlotGeometry.x(BalloonMenu.slotColumn(column)) + INSET;
    }

    public static int y(final int row) {
        return SlotGeometry.y(BalloonMenu.slotRow(row)) + INSET;
    }

    public static Optional<String> overlay(final BalloonMenu.State state, final int row) {
        return switch (state) {
            case OPEN -> Optional.empty();
            case LOCKED -> Optional.of(row == 0 ? Glyphs.GUI_TRAVEL_LOCKED_TOP : Glyphs.GUI_TRAVEL_LOCKED_BOTTOM);
            case HERE -> Optional.of(row == 0 ? Glyphs.GUI_TRAVEL_HERE_TOP : Glyphs.GUI_TRAVEL_HERE_BOTTOM);
        };
    }

    /** Returns the title for these cards. The window has no readable title. */
    public static Component title(final List<BalloonMenu.Entry> entries) {
        final MenuTitle.Canvas canvas = MenuTitle.on(Glyphs.GUI_TRAVEL_PANEL);
        for (final BalloonMenu.Entry entry : entries) {
            overlay(entry.state(), entry.row())
                    .ifPresent(glyph -> canvas.overlay(glyph, x(entry.column()), CARD_WIDTH));
        }
        return canvas.build(Component.empty());
    }
}
