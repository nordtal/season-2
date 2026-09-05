package eu.nordtal.s2.smp.travel;

import eu.nordtal.s2.common.Glyphs;
import eu.nordtal.s2.common.menu.MenuTitle;
import eu.nordtal.s2.common.menu.SlotGeometry;

import net.kyori.adventure.text.Component;

import java.util.List;
import java.util.Optional;

/**
 * Draws the balloon's surface: the travel panel, and a state overlay on every card that needs one.
 *
 * <h2>What is baked in and what is laid over</h2>
 * The panel ({@link Glyphs#GUI_TRAVEL_PANEL}) carries all four world cards, because all four are
 * always shown in the same places. What varies per player is a card's state - {@code LOCKED} gets
 * a shade with a padlock, {@code HERE} gets a white frame, {@code OPEN} gets nothing - and each
 * state is one card-sized glyph declared twice in {@code gui.json}, once per card row at the ascent
 * that lands it there. {@link MenuTitle.Canvas} walks the cursor to the card's x and draws it.
 *
 * <h2>The geometry, and where it is decided</h2>
 * A card is drawn {@link #INSET} pixels inside the slot cells it covers, so the two rows of cards
 * read as separate and the clickable area still ends within two pixels of the art.
 * {@code resource-pack/tools/generate_gui_panels.py} draws the cards from the same three numbers
 * ({@link SlotGeometry}'s origin and pitch, and the inset); {@code TravelPanelTest} reads the panel
 * PNG back and asserts every card is where this class says it is, so the two cannot drift apart
 * without the build saying so.
 */
public final class TravelPanel {

    /** Pixels between a card's edge and the slot cells it covers. */
    public static final int INSET = 2;

    /** A card's drawn width: four slot columns less the inset on both sides. */
    public static final int CARD_WIDTH = BalloonMenu.CARD_COLUMNS * SlotGeometry.PITCH - 2 * INSET;

    /** A card's drawn height: three slot rows less the inset on both sides. */
    public static final int CARD_HEIGHT = BalloonMenu.CARD_ROWS * SlotGeometry.PITCH - 2 * INSET;

    private TravelPanel() {
    }

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
            overlay(entry.state(), entry.row()).ifPresent(glyph ->
                    canvas.overlay(glyph, x(entry.column()), CARD_WIDTH));
        }
        return canvas.build(Component.empty());
    }
}
