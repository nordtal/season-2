package eu.nordtal.season.packrendering.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.packrendering.Glyphs;
import eu.nordtal.season.packrendering.npc.DialogueScreen.Hint;
import eu.nordtal.season.packrendering.npc.DialogueScreen.Mark;
import eu.nordtal.season.packrendering.npc.DialogueScreen.Panel;
import eu.nordtal.season.packrendering.npc.DialogueScreen.Switch;
import eu.nordtal.season.packrendering.ui.Colours;
import eu.nordtal.season.packrendering.ui.Letters;
import java.util.List;
import java.util.stream.IntStream;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.junit.jupiter.api.Test;

/** Where the dialogue's line draws each piece, measured against the assembled pack. */
class DialogueScreenTest {

    private static final String NPC = Glyphs.FONT_NPC;
    private static final List<String> THREE = List.of("Ja", "Nein", "Vielleicht");

    private static Placed.Ink ink(final String font, final TextColor colour, final int x, final String text) {
        return new Placed.Ink(font, colour, x, text);
    }

    private static Placed.Ink art(final int x, final String glyph) {
        return new Placed.Ink(NPC, Colours.ART, x, glyph);
    }

    private static String text(final int shift) {
        return Glyphs.FONT_NPC_TEXT.get(shift);
    }

    private static Placed last(final Panel panel) {
        return Placed.of(DialogueScreen.bottom(DialogueScreen.STAGES, panel, List.of(), null));
    }

    private static Panel answers(final List<String> answers, final int selected) {
        return new Panel("Alma", List.of("Kommst du mit?"), false, answers, selected);
    }

    @Test
    void theSpeakingPanelDrawsItsArtNameAndLinesOnTheirRows() {
        final Placed line = last(new Panel("Alma Berg", List.of("Eins", "Zwei", "Drei"), false, List.of(), 0));

        assertEquals(262, line.advance());
        assertTrue(line.inks().contains(art(0, Glyphs.NPC_PANEL_SPEAK_0)));
        assertTrue(line.inks().contains(art(256, Glyphs.NPC_PANEL_SPEAK_1)));
        assertTrue(line.inks().contains(art(7, Glyphs.NPC_PORTRAIT_SPEAK)));
        assertEquals(ink(text(-6), Colours.INK, 36, "Alma Berg"), line.only("Alma Berg"));
        assertEquals(ink(text(5), Colours.INK, 36, "Eins"), line.only("Eins"));
        assertEquals(ink(text(14), Colours.INK, 36, "Zwei"), line.only("Zwei"));
        assertEquals(ink(text(23), Colours.INK, 36, "Drei"), line.only("Drei"));
        assertTrue(line.in(NPC).stream().noneMatch(ink -> ink.text().equals(Glyphs.NPC_HOLD)));
    }

    @Test
    void theHoldMarkStandsOnTheContentsLastColumns() {
        final Placed line = last(new Panel("Alma", List.of("Eins"), true, List.of(), 0));

        assertEquals(art(250, Glyphs.NPC_HOLD), line.only(Glyphs.NPC_HOLD));
    }

    @Test
    void twoAnswersStandOnTheSecondAndThirdPlatesWhicheverIsSelected() {
        for (int selected = 0; selected < 2; selected++) {
            final Placed line = last(answers(THREE.subList(0, 2), selected));

            assertEquals(262, line.advance());
            assertTrue(line.inks().contains(art(0, Glyphs.NPC_PANEL_2_0)));
            assertTrue(line.inks().contains(art(7, Glyphs.NPC_PORTRAIT_2)));
            assertEquals(ink(text(-37), Colours.INK, 36, "Alma"), line.only("Alma"));
            assertEquals(ink(text(-26), Colours.INK, 36, "Kommst du mit?"), line.only("Kommst du mit?"));
            plate(line, selected, 0, Glyphs.NPC_PLATE_2, Glyphs.NPC_PLATE_ON_2, -1);
            plate(line, selected, 1, Glyphs.NPC_PLATE_3, Glyphs.NPC_PLATE_ON_3, 19);
        }
    }

    @Test
    void threeAnswersStandOnTheirOwnPlatesWhicheverIsSelected() {
        for (int selected = 0; selected < 3; selected++) {
            final Placed line = last(answers(THREE, selected));

            assertEquals(262, line.advance());
            assertTrue(line.inks().contains(art(0, Glyphs.NPC_PANEL_3_0)));
            assertTrue(line.inks().contains(art(256, Glyphs.NPC_PANEL_3_1)));
            assertTrue(line.inks().contains(art(7, Glyphs.NPC_PORTRAIT_3)));
            assertEquals(ink(text(-57), Colours.INK, 36, "Alma"), line.only("Alma"));
            assertEquals(ink(text(-46), Colours.INK, 36, "Kommst du mit?"), line.only("Kommst du mit?"));
            plate(line, selected, 0, Glyphs.NPC_PLATE_1, Glyphs.NPC_PLATE_ON_1, -21);
            plate(line, selected, 1, Glyphs.NPC_PLATE_2, Glyphs.NPC_PLATE_ON_2, -1);
            plate(line, selected, 2, Glyphs.NPC_PLATE_3, Glyphs.NPC_PLATE_ON_3, 19);
        }
    }

    @Test
    void oneAnswerStandsOnTheFirstPlateOfThePanelForTwo() {
        final Placed line = last(answers(List.of("Ja"), 0));

        assertTrue(line.inks().contains(art(0, Glyphs.NPC_PANEL_2_0)));
        plate(line, 0, 0, Glyphs.NPC_PLATE_2, Glyphs.NPC_PLATE_ON_2, -1);
        assertTrue(line.in(NPC).stream().noneMatch(ink -> ink.text().equals(Glyphs.NPC_PLATE_3)));
    }

    private static void plate(
            final Placed line,
            final int selected,
            final int answer,
            final String normal,
            final String on,
            final int shift) {
        final boolean chosen = answer == selected;
        final String number = String.valueOf(answer + 1);
        final String label = THREE.get(answer);
        assertEquals(art(7, chosen ? on : normal), line.only(chosen ? on : normal), label);
        assertEquals(ink(text(shift), chosen ? Colours.ON_ACCENT : Colours.INK, 11, label), line.only(label));
        assertEquals(
                ink(text(shift), chosen ? Colours.ON_ACCENT : Colours.SOFT, 251 - Letters.visibleWidth(number), number),
                line.only(number));
    }

    @Test
    void hintsEndOnTheContentsLastColumnWithTheMainActionLast() {
        final List<Hint> hints = List.of(new Hint(Mark.CHOOSE, "Wählen"), new Hint(Mark.ANSWER, "Antworten"));
        final Placed line = Placed.of(DialogueScreen.bottom(4, answers(THREE, 0), hints, null));

        final int answer = 255 - Letters.visibleWidth("Antworten");
        assertEquals(ink(text(53), Colours.TOOLTIP_TITLE, answer, "Antworten"), line.only("Antworten"));
        assertEquals(art(answer - 4 - 9, Glyphs.NPC_MOUSE_LEFT), line.only(Glyphs.NPC_MOUSE_LEFT));
        final int choose = answer - 4 - 9 - 10 - Letters.visibleWidth("Wählen");
        assertEquals(ink(text(53), Colours.TOOLTIP_TITLE, choose, "Wählen"), line.only("Wählen"));
        assertEquals(art(choose - 4 - 15, Glyphs.NPC_MOUSE_WHEEL), line.only(Glyphs.NPC_MOUSE_WHEEL));
        assertEquals(262, line.advance());
    }

    @Test
    void theNextHintStandsAloneAtTheRight() {
        final Placed line = Placed.of(DialogueScreen.bottom(4, null, List.of(new Hint(Mark.NEXT, "Weiter")), null));

        final int word = 255 - Letters.visibleWidth("Weiter");
        assertEquals(word, line.only("Weiter").x());
        assertEquals(art(word - 13, Glyphs.NPC_MOUSE_RIGHT), line.only(Glyphs.NPC_MOUSE_RIGHT));
    }

    @Test
    void theSwitchShowsItsKeyItsBoxAndItsLabelOnTheBarsLeftEnd() {
        for (final boolean ticked : List.of(false, true)) {
            final Placed line =
                    Placed.of(DialogueScreen.bottom(4, null, List.of(), new Switch("Nicht mehr zeigen", ticked)));
            final String box = ticked ? Glyphs.NPC_CHECK_ON : Glyphs.NPC_CHECK_OFF;
            final String other = ticked ? Glyphs.NPC_CHECK_OFF : Glyphs.NPC_CHECK_ON;

            assertEquals(art(7, Glyphs.NPC_KEY_SHIFT), line.only(Glyphs.NPC_KEY_SHIFT));
            assertEquals(art(41, box), line.only(box));
            assertTrue(line.inks().stream().noneMatch(ink -> ink.text().equals(other)));
            assertEquals(ink(text(53), Colours.TOOLTIP_TITLE, 54, "Nicht mehr zeigen"), line.only("Nicht mehr zeigen"));
            assertEquals(262, line.advance());
        }
    }

    @Test
    void everyStageDrawsItsBottomBarAcrossTheScreenAndAdvances262() {
        final List<String> bars = List.of(Glyphs.NPC_BAR_1, Glyphs.NPC_BAR_2, Glyphs.NPC_BAR_3, Glyphs.NPC_BAR_4);
        for (int stage = 0; stage <= DialogueScreen.STAGES; stage++) {
            final Placed line = Placed.of(DialogueScreen.bottom(stage, null, List.of(), null));
            final String bar = stage == 0 ? "" : bars.get(stage - 1);

            assertEquals(262, line.advance(), "stage " + stage);
            final List<Placed.Ink> expected = stage == 0
                    ? List.of()
                    : IntStream.range(0, 12)
                            .mapToObj(tile -> art(-1405 + 256 * tile, bar))
                            .toList();
            assertEquals(expected, line.inks(), "stage " + stage);
        }
    }

    @Test
    void beforeTheLastStageOnlyTheBarShows() {
        final Panel panel = new Panel("Alma", List.of("Eins"), true, List.of(), 0);
        final List<Hint> hints = List.of(new Hint(Mark.NEXT, "Weiter"));
        final Placed line = Placed.of(DialogueScreen.bottom(3, panel, hints, new Switch("Aus", false)));

        assertEquals(12, line.inks().size());
        assertTrue(line.inks().stream().allMatch(ink -> ink.text().equals(Glyphs.NPC_BAR_3)));
        assertEquals(262, line.advance());
    }

    @Test
    void everyFullScreenAdvances262WhateverItDraws() {
        final List<Hint> hints = List.of(new Hint(Mark.CHOOSE, "Wählen"), new Hint(Mark.ANSWER, "Antworten"));
        final List<Panel> panels = List.of(
                new Panel(
                        "Ein sehr, sehr langer Name, der nicht mehr auf die Zeile passt",
                        List.of(),
                        false,
                        List.of(),
                        0),
                new Panel("Alma", List.of("Eins", "Zwei", "Drei"), true, List.of(), 0),
                answers(List.of("Ja"), 0),
                answers(THREE.subList(0, 2), 1),
                answers(THREE, 2));
        for (final Panel panel : panels) {
            final Component line = DialogueScreen.bottom(4, panel, hints, new Switch("Nicht mehr zeigen", true));
            assertEquals(262, Placed.of(line).advance(), panel.toString());
        }
    }

    @Test
    void theTopBarSpansTheBandAtEveryStageAndIsEmptyBeforeIt() {
        final List<String> bars =
                List.of(Glyphs.NPC_TOP_BAR_1, Glyphs.NPC_TOP_BAR_2, Glyphs.NPC_TOP_BAR_3, Glyphs.NPC_TOP_BAR_4);

        assertEquals(Component.empty(), DialogueScreen.top(0));
        for (int stage = 1; stage <= DialogueScreen.STAGES; stage++) {
            final Placed line = Placed.of(DialogueScreen.top(stage));
            final String bar = bars.get(stage - 1);

            assertEquals(3072, line.advance(), "stage " + stage);
            assertEquals(
                    IntStream.range(0, 12)
                            .mapToObj(tile -> art(256 * tile, bar))
                            .toList(),
                    line.inks());
        }
    }

    @Test
    void aStageOutsideTheOpeningIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> DialogueScreen.top(5));
        assertThrows(IllegalArgumentException.class, () -> DialogueScreen.bottom(-1, null, List.of(), null));
    }

    @Test
    void textTooLongForItsRoomEndsInAnEllipsis() {
        final String name = "Ein sehr, sehr langer Name, der nicht mehr auf die Zeile passt";
        final String label = "Eine Antwort, die viel zu lang ist, um auf eine einzige Platte zu passen";
        final Placed line = last(new Panel(name, List.of(), false, List.of(label), 0));

        final Placed.Ink shown = line.in(text(-37)).getFirst();
        assertTrue(shown.text().endsWith(Letters.ELLIPSIS));
        assertTrue(Letters.visibleWidth(shown.text()) <= DialogueScreen.NAME_ROOM);
        final Placed.Ink plate = line.in(text(-1)).getFirst();
        assertTrue(plate.text().endsWith(Letters.ELLIPSIS));
        assertTrue(Letters.visibleWidth(plate.text()) <= DialogueScreen.LABEL_ROOM);
    }

    @Test
    void aPanelHoldsAtMostThreeAnswersAndTheLinesOfItsHeight() {
        assertThrows(IllegalArgumentException.class, () -> answers(List.of("a", "b", "c", "d"), 0));
        assertThrows(IllegalArgumentException.class, () -> answers(THREE, 3));
        assertThrows(
                IllegalArgumentException.class,
                () -> new Panel("Alma", List.of("1", "2", "3", "4"), false, List.of(), 0));
        assertThrows(IllegalArgumentException.class, () -> new Panel("Alma", List.of("1", "2", "3"), false, THREE, 0));
        assertThrows(IllegalArgumentException.class, () -> new Panel("Alma", List.of(), true, THREE, 0));
    }

    @Test
    void wrapBreaksAtSpacesSoEveryLineInksWithinItsRoom() {
        final String text = "Der Wind kommt heute von Norden, und die Fähre fährt erst, wenn er nachlässt.";

        final List<String> lines = DialogueScreen.wrap(text, DialogueScreen.BODY_ROOM);

        assertTrue(lines.size() > 1);
        assertEquals(text, String.join(" ", lines));
        lines.forEach(line -> assertTrue(Letters.visibleWidth(line) <= DialogueScreen.BODY_ROOM, line));
        final String joined =
                lines.get(0) + " " + lines.get(1).substring(0, lines.get(1).indexOf(' '));
        assertTrue(Letters.visibleWidth(joined) > DialogueScreen.BODY_ROOM, "the first line takes every word it can");
    }

    @Test
    void wrapBreaksAWordOnlyWhenItAloneIsWiderThanTheRoom() {
        final String word = "Donaudampfschifffahrtsgesellschaftskapitän";

        final List<String> lines = DialogueScreen.wrap("ab " + word, 60);

        assertEquals("ab", lines.getFirst());
        assertEquals(word, String.join("", lines.subList(1, lines.size())));
        lines.forEach(line -> assertTrue(Letters.visibleWidth(line) <= 60, line));
    }

    @Test
    void cutKeepsAShortTextAndEndsALongOneInAnEllipsisWithinTheRoom() {
        assertEquals("Alma", DialogueScreen.cut("Alma", DialogueScreen.NAME_ROOM));
        final String cut = DialogueScreen.cut("Ein Name, der für diese Zeile viel zu lang ist", 60);
        assertTrue(cut.endsWith(Letters.ELLIPSIS));
        assertTrue(Letters.visibleWidth(cut) <= 60);
    }

    @Test
    void aPageTooLongForItsLinesEndsItsLastLineInAnEllipsis() {
        final String text = "Eins zwei drei vier fünf sechs sieben acht neun zehn elf zwölf dreizehn vierzehn "
                + "fünfzehn sechzehn siebzehn achtzehn neunzehn zwanzig einundzwanzig zweiundzwanzig";

        final List<String> page = DialogueScreen.page(text, DialogueScreen.QUESTION_LINES);

        assertEquals(DialogueScreen.QUESTION_LINES, page.size());
        assertTrue(page.getLast().endsWith(Letters.ELLIPSIS));
        page.forEach(line -> assertTrue(Letters.visibleWidth(line) <= DialogueScreen.BODY_ROOM, line));
        assertEquals(List.of("Eins zwei"), DialogueScreen.page("Eins zwei", DialogueScreen.SPEAK_LINES));
    }
}
