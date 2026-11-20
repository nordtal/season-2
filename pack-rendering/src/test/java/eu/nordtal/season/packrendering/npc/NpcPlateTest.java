package eu.nordtal.season.packrendering.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.packrendering.Glyphs;
import eu.nordtal.season.packrendering.npc.NpcPlate.Look;
import eu.nordtal.season.packrendering.npc.NpcPlate.Style;
import eu.nordtal.season.packrendering.ui.Letters;
import eu.nordtal.season.packrendering.ui.Pen;
import eu.nordtal.season.packrendering.ui.Symbol;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Where an NPC's name plate draws each piece, measured against the assembled pack. */
class NpcPlateTest {

    private static final TextColor RED = TextColor.color(0xA83232);
    private static final String PLATE = Glyphs.FONT_NPC_PLATE;
    private static final String NAME = "Schmiedin";

    private static Look look(final Style style, final @Nullable Symbol icon) {
        return new Look(style, RED, icon);
    }

    @ParameterizedTest
    @EnumSource(Style.class)
    void thePanelAndTheInkTakeThePlatesWidthWithAndWithoutIconAndBubble(final Style style) {
        for (final Symbol icon : new Symbol[] {null, Symbol.PICKAXE}) {
            for (final boolean bubble : new boolean[] {false, true}) {
                final Look look = look(style, icon);
                final int width = NpcPlate.width(look, NAME);
                assertEquals(
                        width, Placed.of(NpcPlate.panel(look, NAME, bubble)).advance(), style + " panel");
                assertEquals(width, Placed.of(NpcPlate.ink(look, NAME, bubble)).advance(), style + " ink");
            }
        }
    }

    @Test
    void eachStyleStartsTheBoldWhiteNameWhereTheRedesignDoes() {
        final int name = Letters.boldVisibleWidth(NAME);
        final Map<Style, int[]> expected = Map.of(
                Style.PILL, new int[] {7, 18, 7 + name + 7},
                Style.DARK, new int[] {11, 22, 11 + name + 7},
                Style.RIBBON, new int[] {10, 21, 10 + name + 10},
                Style.BADGE, new int[] {19, 19, 19 + name + 7});
        for (final Style style : Style.values()) {
            final int[] at = expected.get(style);
            final Placed plain = Placed.of(NpcPlate.ink(look(style, null), NAME, false));
            final Placed withIcon = Placed.of(NpcPlate.ink(look(style, Symbol.PICKAXE), NAME, false));
            assertEquals(
                    new Placed.Ink(Pen.textFont(4), NamedTextColor.WHITE, at[0], NAME, true),
                    plain.only(NAME),
                    style.name());
            assertEquals(at[1], withIcon.only(NAME).x(), style.name());
            assertEquals(at[2], NpcPlate.width(look(style, null), NAME), style.name());
        }
    }

    @Test
    void thePillIsTintedToTheColourWithItsLightRowInTheInk() {
        final Look look = look(Style.PILL, null);
        final Placed panel = Placed.of(NpcPlate.panel(look, NAME, false));
        final Placed ink = Placed.of(NpcPlate.ink(look, NAME, false));
        final int width = NpcPlate.width(look, NAME);

        assertTrue(panel.in(PLATE).stream().allMatch(piece -> RED.equals(piece.colour())));
        assertEquals(0, panel.only(Glyphs.NPC_PLATE_PILL.get(0)).x());
        assertEquals(width - 3, panel.only(Glyphs.NPC_PLATE_PILL.get(1)).x());
        final List<Placed.Ink> light = ink.in(PLATE);
        assertEquals(2, light.getFirst().x());
        assertTrue(light.stream()
                .allMatch(piece -> DialogueScreen.lighter(RED, 0.25).equals(piece.colour())));
    }

    @Test
    void theDarkPlateKeepsItsOwnColoursAndShowsTheColourInItsStripeAndIcon() {
        final Look look = look(Style.DARK, Symbol.SHIELD);
        final Placed panel = Placed.of(NpcPlate.panel(look, NAME, false));
        final Placed ink = Placed.of(NpcPlate.ink(look, NAME, false));

        assertTrue(panel.in(PLATE).stream().allMatch(piece -> NamedTextColor.WHITE.equals(piece.colour())));
        assertEquals(new Placed.Ink(PLATE, RED, 2, Glyphs.NPC_PLATE_STRIPE, false), ink.only(Glyphs.NPC_PLATE_STRIPE));
        assertEquals(
                new Placed.Ink(Pen.textFont(4), DialogueScreen.lighter(RED, 0.55), 11, Glyphs.UI_SYMBOL_SHIELD, false),
                ink.only(Glyphs.UI_SYMBOL_SHIELD));
    }

    @Test
    void theBadgeHoldsTheIconOrAStarInItsCircle() {
        final Placed plain = Placed.of(NpcPlate.ink(look(Style.BADGE, null), NAME, false));
        final Placed withIcon = Placed.of(NpcPlate.ink(look(Style.BADGE, Symbol.HEART), NAME, false));
        final Placed panel = Placed.of(NpcPlate.panel(look(Style.BADGE, null), NAME, false));

        assertEquals(4, plain.only(Glyphs.UI_SYMBOL_STAR).x());
        assertEquals(4, withIcon.only(Glyphs.UI_SYMBOL_HEART).x());
        assertEquals(
                new Placed.Ink(PLATE, RED, 0, Glyphs.NPC_PLATE_CIRCLE, false), panel.only(Glyphs.NPC_PLATE_CIRCLE));
        assertEquals(
                DialogueScreen.lighter(RED, 0.2),
                plain.only(Glyphs.NPC_PLATE_CAP).colour());
    }

    @Test
    void theBubbleStandsOverThePlatesMiddleItsDotsInTheColour() {
        final Look look = look(Style.RIBBON, null);
        final int width = NpcPlate.width(look, NAME);
        final Placed panel = Placed.of(NpcPlate.panel(look, NAME, true));
        final Placed ink = Placed.of(NpcPlate.ink(look, NAME, true));

        final int x = panel.only(Glyphs.NPC_SPEECH_BUBBLE).x();
        assertTrue(Math.abs(x + NpcPlate.BUBBLE - (width - x)) <= 1, "centred: " + x + " in " + width);
        assertEquals(new Placed.Ink(PLATE, RED, x, Glyphs.NPC_SPEECH_DOTS, false), ink.only(Glyphs.NPC_SPEECH_DOTS));
    }
}
