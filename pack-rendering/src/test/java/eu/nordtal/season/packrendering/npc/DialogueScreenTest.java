package eu.nordtal.season.packrendering.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.packrendering.Glyphs;
import eu.nordtal.season.packrendering.npc.DialogueScreen.Hint;
import eu.nordtal.season.packrendering.npc.DialogueScreen.Icon;
import eu.nordtal.season.packrendering.npc.DialogueScreen.Mark;
import eu.nordtal.season.packrendering.npc.DialogueScreen.Page;
import eu.nordtal.season.packrendering.npc.DialogueScreen.Panel;
import eu.nordtal.season.packrendering.npc.DialogueScreen.Speaker;
import eu.nordtal.season.packrendering.npc.DialogueScreen.Switch;
import eu.nordtal.season.packrendering.ui.Colours;
import eu.nordtal.season.packrendering.ui.Letters;
import java.util.List;
import java.util.stream.IntStream;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.junit.jupiter.api.Test;

/** Where the dialogue's line draws each piece, measured against the assembled pack. */
class DialogueScreenTest {

    private static final String NPC = Glyphs.FONT_NPC;
    private static final Speaker ALMA = new Speaker("Alma");
    private static final List<String> THREE = List.of("Ja", "Nein", "Vielleicht");
    private static final List<Integer> HEIGHTS =
            List.of(48, 51, 56, 58, 61, 66, 68, 70, 71, 76, 78, 80, 81, 88, 90, 100);

    private static String text(final int shift) {
        return Glyphs.FONT_NPC_TEXT.get(shift);
    }

    private static String art(final int shift) {
        return Glyphs.FONT_NPC_ART.get(shift);
    }

    private static String small(final int shift) {
        return Glyphs.FONT_NPC_SMALL.get(shift);
    }

    private static Placed last(final Panel panel) {
        return Placed.of(DialogueScreen.bottom(DialogueScreen.STAGES, panel, List.of(), null));
    }

    private static int index(final int height) {
        return HEIGHTS.indexOf(height);
    }

    @Test
    void theSpeakingPanelDrawsItsArtPortraitAndLinesOnTheirRows() {
        final Placed placed = last(Panel.speaking(ALMA, List.of("Hallo", "du", "da"), false));

        assertEquals(0, placed.only(Glyphs.NPC_PANELS_LEFT.get(index(51))).x());
        assertEquals(256, placed.only(Glyphs.NPC_PANELS_RIGHT.get(index(51))).x());
        assertEquals(6, placed.only(Glyphs.NPC_PORTRAITS.get(index(51))).x());
        assertEquals(new Placed.Ink(text(17), DialogueScreen.INK, 60, "Hallo", false), placed.only("Hallo"));
        assertEquals(text(27), placed.only("du").font());
        assertEquals(text(37), placed.only("da").font());
    }

    @Test
    void longerSpeakingTextsGrowThePanelUpToSixLines() {
        final Placed four = last(Panel.speaking(ALMA, List.of("a1", "a2", "a3", "a4"), false));
        assertEquals(0, four.only(Glyphs.NPC_PANELS_LEFT.get(index(61))).x());
        assertEquals(text(7), four.only("a1").font());
        assertEquals(text(37), four.only("a4").font());

        final Placed six = last(Panel.speaking(ALMA, List.of("a1", "a2", "a3", "a4", "a5", "a6"), false));
        assertEquals(0, six.only(Glyphs.NPC_PANELS_LEFT.get(index(81))).x());
        assertEquals(text(-13), six.only("a1").font());
        assertEquals(text(37), six.only("a6").font());
    }

    @Test
    void speakingLinesAreTopAnchoredAtAFixedDistanceBelowTheNameTab() {
        for (int count = 1; count <= DialogueScreen.SPEAK_LINES; count++) {
            final List<String> lines =
                    IntStream.range(0, count).mapToObj(i -> "line" + i).toList();
            final Panel panel = Panel.speaking(ALMA, lines, false);
            final int top = 16 + panel.height();
            final int firstB = DialogueScreen.firstLineBottom(panel);
            final int lastCapBottom = firstB - 10 * (count - 1) - 6;

            assertEquals(top - 12, firstB, "fixed top anchor for " + count + " lines");
            assertTrue(
                    lastCapBottom - 19 >= 10, "extra bottom padding for " + count + " lines: " + (lastCapBottom - 19));
            final Placed placed = last(panel);
            assertEquals(text(72 - firstB), placed.only("line0").font());
        }
    }

    @Test
    void theNameTabCarriesTheBoldWhiteNameInItsColourAndFitsIt() {
        final Speaker speaker = new Speaker("Alma", TextColor.color(0xA83232), null, NPC);
        final Placed placed = last(Panel.speaking(speaker, List.of("Hallo"), false));
        final int width = 6 + Letters.boldVisibleWidth("Alma") + 6;

        final Placed.Ink name = placed.only("Alma");
        assertEquals(new Placed.Ink(text(68 - 64), NamedTextColor.WHITE, 62, "Alma", true), name);
        final List<Placed.Ink> ends = placed.in(art(65 - 64)).stream()
                .filter(ink -> ink.text().equals(Glyphs.NPC_TAB_END))
                .toList();
        assertEquals(
                List.of(56, 56 + width - 1), ends.stream().map(Placed.Ink::x).toList());
        assertEquals(
                DialogueScreen.darker(TextColor.color(0xA83232), 0.45),
                ends.getFirst().colour());
        assertTrue(placed.in(art(65 - 64)).stream()
                .anyMatch(ink -> ink.colour().equals(TextColor.color(0xA83232)) && ink.x() == 57));
    }

    @Test
    void anIconStandsBeforeTheNameInTheTab() {
        final Speaker speaker = new Speaker("Alma", DialogueScreen.DEFAULT_TAB, Icon.PICKAXE, NPC);
        final Placed placed = last(Panel.speaking(speaker, List.of("Hallo"), false));

        assertEquals(62, placed.only(Icon.PICKAXE.glyph()).x());
        assertEquals(73, placed.only("Alma").x());
    }

    @Test
    void aLongNameIsCutInBoldToItsRoom() {
        final String name = "Wilhelmine Augusta von Kranichfeld-Oberhausen zu Altenstein";
        final Placed placed = last(Panel.speaking(new Speaker(name), List.of("Hallo"), false));

        final String drawn = DialogueScreen.boldCut(name, DialogueScreen.NAME_ROOM);
        assertTrue(drawn.endsWith(Letters.ELLIPSIS), drawn);
        assertTrue(Letters.boldVisibleWidth(placed.only(drawn).text()) <= DialogueScreen.NAME_ROOM);
    }

    @Test
    void theSelectedAnswerShowsItsStripChevronAndBoldAccentLabel() {
        final Placed placed = last(Panel.question(ALMA, List.of("Kommst du mit?"), THREE, 1));

        // Answer 2 of 3 stands on row 1 from the bottom, whose top is 48 rows up.
        assertEquals(
                new Placed.Ink(art(72 - 48), Colours.ART, 56, Glyphs.NPC_STRIP, false), placed.only(Glyphs.NPC_STRIP));
        assertEquals(
                new Placed.Ink(art(72 - 46), Colours.ART, 60, Glyphs.NPC_CHEVRON, false),
                placed.only(Glyphs.NPC_CHEVRON));
        assertEquals(new Placed.Ink(text(72 - 46), DialogueScreen.ACCENT, 68, "Nein", true), placed.only("Nein"));
        assertEquals(new Placed.Ink(text(72 - 58), DialogueScreen.SOFT, 68, "Ja", false), placed.only("Ja"));
        assertEquals(
                new Placed.Ink(text(72 - 34), DialogueScreen.SOFT, 68, "Vielleicht", false), placed.only("Vielleicht"));
    }

    @Test
    void theAnswersKeepTheirPlacesWhicheverIsSelected() {
        for (int selected = 0; selected < 3; selected++) {
            final Placed placed = last(Panel.question(ALMA, List.of("Kommst du mit?"), THREE, selected));
            for (int answer = 0; answer < 3; answer++) {
                final Placed.Ink label = placed.only(THREE.get(answer));
                assertEquals(68, label.x());
                assertEquals(text(72 - (34 + 12 * (2 - answer))), label.font());
            }
        }
    }

    @Test
    void theNumbersEndBeforeTheMarkColumnInTheSmallLetters() {
        final Placed placed = last(Panel.question(ALMA, List.of("Kommst du mit?"), THREE, 0));

        for (int answer = 0; answer < 3; answer++) {
            final Placed.Ink number = placed.only(String.valueOf(answer + 1));
            assertEquals(291, number.x() + SmallFont.visibleWidth(number.text()));
            assertEquals(small(72 - (33 + 12 * (2 - answer))), number.font());
            assertEquals(answer == 0 ? DialogueScreen.ACCENT : DialogueScreen.FAINT, number.colour());
        }
    }

    @Test
    void aQuestionTypingKeepsTheHeightItsAnswersWillTake() {
        final Panel typing = new Panel(ALMA, List.of("Kommst"), 3, List.of(), 0, false, null);
        final Placed placed = last(typing);

        assertEquals(0, placed.only(Glyphs.NPC_PANELS_LEFT.get(index(70))).x());
        assertTrue(placed.inks().stream().noneMatch(ink -> ink.text().equals(Glyphs.NPC_STRIP)));
    }

    @Test
    void everyQuestionShapeHasItsPanelAndPortrait() {
        for (int lines = 1; lines <= DialogueScreen.QUESTION_LINES; lines++) {
            for (int answers = 1; answers <= 3; answers++) {
                final List<String> text =
                        IntStream.range(0, lines).mapToObj(i -> "z" + i).toList();
                final Panel panel = Panel.question(ALMA, text, THREE.subList(0, answers), 0);
                final Placed placed = last(panel);
                final int height = Math.max(48, 46 + 10 * (lines - 1) + 12 * (answers - 1));
                assertEquals(
                        0,
                        placed.only(Glyphs.NPC_PANELS_LEFT.get(index(height))).x(),
                        lines + "/" + answers);
                final int first = 16 + height - 12;
                assertEquals(text(72 - first), placed.only("z0").font());
            }
        }
    }

    @Test
    void theMoreMarkAndTheCountStandAtTheRightOfTheBottomPadding() {
        final Panel panel = new Panel(ALMA, List.of("a", "b", "c", "d"), 0, List.of(), 0, true, new Page(1, 2));
        final Placed placed = last(panel);

        assertEquals(new Placed.Ink(NPC, Colours.ART, 288, Glyphs.NPC_MORE, false), placed.only(Glyphs.NPC_MORE));
        final Placed.Ink count = placed.only("1/2");
        assertEquals(new Placed.Ink(small(45), DialogueScreen.FAINT, count.x(), "1/2", false), count);
        assertEquals(284, count.x() + SmallFont.visibleWidth("1/2"));
    }

    @Test
    void aMultiPageSpeakingStepShowsItsCountOnTheLastPageTooWhileAnswersHideIt() {
        final Panel speakingLast = new Panel(ALMA, List.of("a"), 0, List.of(), 0, true, new Page(2, 2));
        assertEquals("2/2", last(speakingLast).only("2/2").text());

        final Panel questionLast = new Panel(ALMA, List.of("a"), 3, THREE, 0, false, new Page(2, 2));
        assertTrue(
                last(questionLast).inks().stream().noneMatch(ink -> ink.text().contains("/")));
    }

    @Test
    void hintsEndAtTheRightWithTheMainActionLast() {
        final List<Hint> hints = List.of(new Hint(Mark.CHOOSE, "Choose"), new Hint(Mark.ANSWER, "Answer"));
        final Placed placed = Placed.of(DialogueScreen.bottom(DialogueScreen.STAGES, null, hints, null));

        final Placed.Ink answer = placed.only("Answer");
        assertEquals(new Placed.Ink(small(62), DialogueScreen.HINT, answer.x(), "Answer", false), answer);
        assertEquals(298, answer.x() + SmallFont.visibleWidth("Answer"));
        assertEquals(answer.x() - 3 - 7, placed.only(Glyphs.NPC_MOUSE_RIGHT).x());
        final Placed.Ink choose = placed.only("Choose");
        assertEquals(placed.only(Glyphs.NPC_MOUSE_RIGHT).x() - 9, choose.x() + SmallFont.visibleWidth("Choose"));
        assertEquals(choose.x() - 10, placed.only(Glyphs.NPC_MOUSE_WHEEL).x());
    }

    @Test
    void theSwitchShowsItsBoxItsWordAndTheShiftKey() {
        final Placed placed =
                Placed.of(DialogueScreen.bottom(DialogueScreen.STAGES, null, List.of(), new Switch("Skip", true)));

        assertEquals(2, placed.only(Glyphs.NPC_CHECK_ON).x());
        assertEquals(13, placed.only("Skip").x());
        assertEquals(
                13 + SmallFont.visibleWidth("Skip") + 4,
                placed.only(Glyphs.NPC_KEY_SHIFT).x());
    }

    @Test
    void everyStageDrawsItsBandAcrossTheScreen() {
        for (int stage = 1; stage <= DialogueScreen.STAGES; stage++) {
            final String tile = Glyphs.NPC_BANDS.get(stage - 1);
            final List<Integer> xs = Placed.of(DialogueScreen.bottom(stage, null, List.of(), null)).inks().stream()
                    .filter(ink -> ink.text().equals(tile))
                    .map(Placed.Ink::x)
                    .toList();
            assertEquals(IntStream.range(0, 12).mapToObj(i -> -1386 + 256 * i).toList(), xs);
        }
        assertEquals(
                List.of(),
                Placed.of(DialogueScreen.bottom(0, null, List.of(), null)).inks());
    }

    @Test
    void beforeTheLastStageOnlyTheBandShows() {
        final Panel panel = Panel.speaking(ALMA, List.of("Hallo"), true);
        final Placed placed = Placed.of(DialogueScreen.bottom(2, panel, List.of(new Hint(Mark.NEXT, "Next")), null));

        assertTrue(placed.inks().stream().allMatch(ink -> ink.text().equals(Glyphs.NPC_BANDS.get(1))));
    }

    @Test
    void everyScreenAdvancesThePanelsWidth() {
        final List<Hint> hints = List.of(new Hint(Mark.NEXT, "Next"));
        for (int stage = 0; stage <= DialogueScreen.STAGES; stage++) {
            assertEquals(
                    300,
                    Placed.of(DialogueScreen.bottom(stage, null, hints, null)).advance());
            assertEquals(
                    300,
                    Placed.of(DialogueScreen.bottom(
                                    stage,
                                    Panel.question(
                                            new Speaker("Alma", DialogueScreen.DEFAULT_TAB, Icon.STAR, NPC),
                                            List.of("Kommst du mit?"),
                                            THREE,
                                            2),
                                    hints,
                                    new Switch("Skip", false)))
                            .advance());
        }
    }

    @Test
    void aStageOutsideTheOpeningIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> DialogueScreen.bottom(5, null, List.of(), null));
        assertThrows(IllegalArgumentException.class, () -> DialogueScreen.bottom(-1, null, List.of(), null));
    }

    @Test
    void aPanelHoldsTheLinesAndAnswersOfItsShape() {
        assertThrows(
                IllegalArgumentException.class,
                () -> Panel.speaking(ALMA, List.of("1", "2", "3", "4", "5", "6", "7"), false));
        assertThrows(
                IllegalArgumentException.class, () -> Panel.question(ALMA, List.of("1", "2", "3", "4", "5"), THREE, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> Panel.question(ALMA, List.of("1"), List.of("a", "b", "c", "d"), 0));
        assertThrows(IllegalArgumentException.class, () -> Panel.question(ALMA, List.of("1"), THREE, 3));
        assertThrows(IllegalArgumentException.class, () -> new Panel(ALMA, List.of("1"), 2, THREE, 0, false, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new Panel(ALMA, List.of("1"), 3, THREE, 0, false, new Page(1, 2)));
    }

    @Test
    void cutKeepsAShortTextAndEndsALongOneInAnEllipsisWithinTheRoom() {
        assertEquals("Alma", DialogueScreen.cut("Alma", 40));
        final String cut = DialogueScreen.cut("Wilhelmine von Kranichfeld", 40);
        assertTrue(cut.endsWith(Letters.ELLIPSIS));
        assertTrue(Letters.visibleWidth(cut) <= 40);
        assertFalse(DialogueScreen.boldCut("Wilhelmine", 40).equals("Wilhelmine"));
    }
}
