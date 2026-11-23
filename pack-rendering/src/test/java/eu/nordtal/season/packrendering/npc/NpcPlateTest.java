package eu.nordtal.season.packrendering.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.packrendering.Glyphs;
import eu.nordtal.season.packrendering.npc.NpcPlate.Look;
import eu.nordtal.season.packrendering.ui.Letters;
import eu.nordtal.season.packrendering.ui.Pen;
import eu.nordtal.season.packrendering.ui.Symbol;
import java.util.List;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.junit.jupiter.api.Test;

/** Where an NPC's name plate draws each piece, measured against the assembled pack. */
class NpcPlateTest {

    private static final TextColor RED = TextColor.color(0xA83232);
    private static final String PLATE = Glyphs.FONT_NPC_PLATE;
    private static final String NAME = "Schmiedin";
    private static final Look PLAIN = new Look(RED, null);
    private static final Look WITH_ICON = new Look(RED, Symbol.PICKAXE);

    @Test
    void thePanelAndTheInkTakeThePlatesWidthWithAndWithoutIconAndBubble() {
        for (final Look look : new Look[] {PLAIN, WITH_ICON}) {
            for (final boolean bubble : new boolean[] {false, true}) {
                final int width = NpcPlate.width(look, NAME);
                assertEquals(
                        width, Placed.of(NpcPlate.panel(look, NAME, bubble)).advance(), look + " panel");
                assertEquals(width, Placed.of(NpcPlate.ink(look, NAME, bubble)).advance(), look + " ink");
            }
        }
    }

    @Test
    void theNameIsRegularWhiteAndThreeRowsBelowThePillsTop() {
        final int name = Letters.visibleWidth(NAME);
        final Placed plain = Placed.of(NpcPlate.ink(PLAIN, NAME, false));
        final Placed withIcon = Placed.of(NpcPlate.ink(WITH_ICON, NAME, false));

        assertEquals(new Placed.Ink(Pen.textFont(5), NamedTextColor.WHITE, 7, NAME, false), plain.only(NAME));
        assertEquals(
                new Placed.Ink(Pen.textFont(5), NamedTextColor.WHITE, 7, Glyphs.UI_SYMBOL_PICKAXE, false),
                withIcon.only(Glyphs.UI_SYMBOL_PICKAXE));
        assertEquals(18, withIcon.only(NAME).x());
        assertEquals(7 + name + 7, NpcPlate.width(PLAIN, NAME));
        assertEquals(7 + 11 + name + 7, NpcPlate.width(WITH_ICON, NAME));
    }

    @Test
    void thePillIsTintedToTheColourWithItsLightRowInTheInk() {
        final Placed panel = Placed.of(NpcPlate.panel(PLAIN, NAME, false));
        final Placed ink = Placed.of(NpcPlate.ink(PLAIN, NAME, false));
        final int width = NpcPlate.width(PLAIN, NAME);

        assertTrue(panel.in(PLATE).stream().allMatch(piece -> RED.equals(piece.colour())));
        assertEquals(0, panel.only(Glyphs.NPC_PLATE_PILL.get(0)).x());
        assertEquals(width - 3, panel.only(Glyphs.NPC_PLATE_PILL.get(1)).x());
        final List<Placed.Ink> light = ink.in(PLATE);
        assertEquals(2, light.getFirst().x());
        assertTrue(light.stream()
                .allMatch(piece -> DialogueScreen.lighter(RED, 0.25).equals(piece.colour())));
    }

    @Test
    void theBubbleStandsOverThePlatesMiddleItsDotsInTheColour() {
        final int width = NpcPlate.width(PLAIN, NAME);
        final Placed panel = Placed.of(NpcPlate.panel(PLAIN, NAME, true));
        final Placed ink = Placed.of(NpcPlate.ink(PLAIN, NAME, true));

        final int x = panel.only(Glyphs.NPC_SPEECH_BUBBLE).x();
        assertTrue(Math.abs(x + NpcPlate.BUBBLE - (width - x)) <= 1, "centred: " + x + " in " + width);
        assertEquals(new Placed.Ink(PLATE, RED, x, Glyphs.NPC_SPEECH_DOTS, false), ink.only(Glyphs.NPC_SPEECH_DOTS));
    }
}
