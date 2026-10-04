package eu.nordtal.s2.smp.travel;

import eu.nordtal.s2.packrendering.Glyphs;
import eu.nordtal.s2.papercommon.menu.MenuTitle;
import eu.nordtal.s2.papercommon.menu.SlotGeometry;
import java.util.List;
import java.util.Optional;
import net.kyori.adventure.text.Component;

/**
 * Draws the balloon's surface: the travel panel, plus a state overlay on every card that needs one.
 *
 * Cards sit {@link #INSET} pixels inside their slots; {@code TravelPanelTest} holds the panel PNG to this geometry.
 */
public final class TravelPanel {

    /** Pixels between a card's edge and the slot cells it covers. */
    public static final int INSET = 2;

    /** A card's drawn width: four slot columns less the inset on both sides. */
    public static final int CARD_WIDTH = BalloonMenu.CARD_COLUMNS * SlotGeometry.PITCH - 2 * INSET;

    /** A card's drawn height: three slot rows less the inset on both sides. */
    public static final int CARD_HEIGHT = BalloonMenu.CARD_ROWS * SlotGeometry.PITCH - 2 * INSET;

    private TravelPanel() {}

    /** The x of card column {@code column}'s left edge, in window pixels. */
    public static int x(final int column) {
        return SlotGeometry.x(BalloonMenu.slotColumn(column)) + INSET;
    }

    /** The y of card row {@code row}'s top edge, in window pixels. */
    public static int y(final int row) {
        return SlotGeometry.y(BalloonMenu.slotRow(row)) + INSET;
    }

    /** The overlay glyph a card in this state needs on this row, if any. */
    public static Optional<String> overlay(final BalloonMenu.State state, final int row) {
        return switch (state) {
            case OPEN -> Optional.empty();
            case LOCKED -> Optional.of(row == 0 ? Glyphs.GUI_TRAVEL_LOCKED_TOP : Glyphs.GUI_TRAVEL_LOCKED_BOTTOM);
            case HERE -> Optional.of(row == 0 ? Glyphs.GUI_TRAVEL_HERE_TOP : Glyphs.GUI_TRAVEL_HERE_BOTTOM);
        };
    }

    /** The whole surface for these cards, as the inventory title. There is no readable title. */
    public static Component title(final List<BalloonMenu.Entry> entries) {
        final MenuTitle.Canvas canvas = MenuTitle.on(Glyphs.GUI_TRAVEL_PANEL);
        for (final BalloonMenu.Entry entry : entries) {
            overlay(entry.state(), entry.row())
                    .ifPresent(glyph -> canvas.overlay(glyph, x(entry.column()), CARD_WIDTH));
        }
        return canvas.build(Component.empty());
    }
}
