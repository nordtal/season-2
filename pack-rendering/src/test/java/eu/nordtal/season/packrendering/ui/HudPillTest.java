package eu.nordtal.season.packrendering.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.packrendering.Glyphs;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import org.junit.jupiter.api.Test;

/** The HUD's pills as the third iteration's sheets measure them, walked with the pack's own advances. */
class HudPillTest {

    @Test
    void aSymbolStandsFiveBeforeTheTextAndThePillPadsFiveOnEitherSide() {
        final Pill.Content station = Pill.Content.of(Symbol.RAIL, "Fjordmarkt");

        assertEquals(5 + 7 + 5 + Letters.visibleWidth("Fjordmarkt") + 5, Pill.width(station));
        assertEquals(Pill.width(station), Advances.of(Pill.of(station)));
    }

    @Test
    void anArrowAfterTheTextStandsFiveAfterIt() {
        final Pill.Content alive = Pill.Content.of(Symbol.HEART, "Lebend 9").then(Symbol.arrow(5));

        assertEquals(5 + 12 + Letters.visibleWidth("Lebend 9") + 5 + 7 + 5, Pill.width(alive));
        assertEquals(Pill.width(alive), Advances.of(Pill.of(alive)));
    }

    @Test
    void theRideBarIsAHundredWideAndFourBeforeItsDistance() {
        final Pill.Content bar = Pill.Content.bar(0.6, "1.364 m");

        assertEquals(5 + 100 + 4 + Letters.visibleWidth("1.364 m") + 5, Pill.width(bar));
        assertEquals(Pill.width(bar), Advances.of(Pill.of(bar)));
    }

    @Test
    void theBarFillsItsNinetyEightColumnsAndShowsAnyStart() {
        assertEquals(0, Pill.filled(0));
        assertEquals(0, Pill.filled(Double.NaN));
        assertEquals(1, Pill.filled(0.001));
        assertEquals(59, Pill.filled(0.6));
        assertEquals(98, Pill.filled(1));
        assertEquals(98, Pill.filled(7));
    }

    @Test
    void aLineIsItsPillsThreeApartAndEndsOnTheLast() {
        final List<Pill.Content> ride =
                List.of(Pill.Content.of(Symbol.RAIL, "Fjordmarkt"), Pill.Content.bar(0, "3.410 m"));

        assertEquals(
                Pill.width(ride.get(0)) + 3 + Pill.width(ride.get(1)),
                Advances.of(Pill.line(ride)),
                "the client centres a boss bar name by its advance");
    }

    @Test
    void theHudAndAHologramDrawTheSamePill() {
        final String hud = plain(Pill.of(Pill.Content.of(Symbol.RAIL, "Fjordmarkt")));
        final String hologram = plain(Pill.of(Symbol.RAIL, "Fjordmarkt"));

        assertTrue(hud.startsWith(Glyphs.UI_PILL.get(0)), "the HUD's pill opens with the pill's end");
        assertTrue(hologram.startsWith(Glyphs.UI_PILL.get(0)), "a hologram's pill opens with the pill's end");
    }

    @Test
    void aPillsPanelAndInkAreDrawnApartWithTheWholePillsAdvance() {
        final List<Pill.Run> runs = List.of(new Pill.Run("Nordwacht", Colours.INK));
        final Component whole = Pill.of(Symbol.RAIL, runs, Pen.Layer.ALL);
        final String panel = plain(Pill.of(Symbol.RAIL, runs, Pen.Layer.ART));
        final String ink = plain(Pill.of(Symbol.RAIL, runs, Pen.Layer.INK));

        assertEquals(Pill.of(Symbol.RAIL, runs), whole);
        assertEquals(Advances.of(whole), Advances.of(Pill.of(Symbol.RAIL, runs, Pen.Layer.ART)));
        assertEquals(Advances.of(whole), Advances.of(Pill.of(Symbol.RAIL, runs, Pen.Layer.INK)));
        assertTrue(panel.startsWith(Glyphs.UI_PILL.get(0)), "the panel opens with the pill's end");
        assertFalse(panel.contains("Nordwacht") || panel.contains(Symbol.RAIL.glyph()), "the panel holds no ink");
        assertTrue(ink.contains("Nordwacht") && ink.contains(Symbol.RAIL.glyph()), "the ink holds the symbol and text");
        assertTrue(Glyphs.UI_PILL.stream().noneMatch(ink::contains), "the ink holds no part of the panel");
    }

    private static String plain(final Component component) {
        final StringBuilder out = new StringBuilder();
        if (component instanceof TextComponent text) {
            out.append(text.content());
        }
        component.children().forEach(child -> out.append(plain(child)));
        return out.toString();
    }
}
