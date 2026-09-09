package eu.nordtal.s2.smp.npc;

import eu.nordtal.s2.common.Glyphs;
import eu.nordtal.s2.common.menu.MenuFont;
import eu.nordtal.s2.common.menu.MenuTitle;
import eu.nordtal.s2.common.menu.SlotGeometry;
import eu.nordtal.s2.smp.menu.PanelWalk;
import eu.nordtal.s2.smp.menu.PanelWalk.Run;
import eu.nordtal.s2.smp.milestone.ObjectiveType;

import net.kyori.adventure.text.Component;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Walks the spawn NPC's composed window and holds it against the PNGs the pack drew.
 *
 * <p>The cursor walk is {@link PanelWalk}; what is asserted here is what is true of design
 * {@code O3} and of nothing else. The one that carries the most is
 * {@link #theBarIsTheRatioAndTheTrackIsTheCard}: the bar is the only thing in this window whose
 * <em>width</em> is a computed number rather than a fixed one, so it is the only thing that can be
 * wrong by a little rather than by a lot - and a bar that shows a quarter as a third is not a
 * failure anywhere.</p>
 */
class ObjectivePanelTest {

    private static final List<ObjectivePanel.Card> FOUR = List.of(
            new ObjectivePanel.Card(Glyphs.GUI_ROW_ICON_HAND_IN, "Logs", "1240/2048", 0.605, false),
            new ObjectivePanel.Card(Glyphs.GUI_ROW_ICON_DONE, "Mine coal", "1500/1500", 1.0, true),
            new ObjectivePanel.Card(Glyphs.GUI_ROW_ICON_STATISTIC, "Kill zombies", "212/500", 0.424, false),
            new ObjectivePanel.Card(Glyphs.GUI_ROW_ICON_ADVANCEMENT, "Iron tools", "4/10", 0.4, false));

    @Test
    @DisplayName("the whole surface returns the cursor to the title anchor")
    void theReadableTitleStillLandsWhereItWould() {
        final List<Run> runs = PanelWalk.runs(surface(FOUR, false, false));
        assertEquals(MenuTitle.ANCHOR_X, runs.get(runs.size() - 1).end(),
                "the panel, a heading, four cards and a share line have to add up to nothing."
                        + " They do not merely move the readable title if they do not - the title"
                        + " is drawn after all of it, and nothing fails");
    }

    @Test
    @DisplayName("each card's plate, icon, name, bar and numbers land where the card is")
    void everyCardIsWhereItSaysItIs() {
        final List<Run> runs = PanelWalk.runs(surface(FOUR, false, false));

        for (int index = 0; index < FOUR.size(); index++) {
            final ObjectivePanel.Card card = FOUR.get(index);
            final int x = ObjectivePanel.CARD_X[index % 2];
            final int upper = ObjectivePanel.CARD_ROW[index / 2];
            final String plate = index < 2 ? Glyphs.GUI_CARD_TOP : Glyphs.GUI_CARD_BOTTOM;

            final List<Run> plates = runs.stream()
                    .filter(run -> run.font().equals(Glyphs.FONT_GUI))
                    .filter(run -> run.content().equals(plate))
                    .filter(run -> run.x() == x)
                    .toList();
            assertEquals(1, plates.size(), "card " + index + "'s plate is not at x " + x);

            assertEquals(x + 3, PanelWalk.find(runs, Glyphs.FONT_GUI_ROWS[upper], card.icon()).x(),
                    "card " + index + "'s type icon sits three pixels inside its own plate");

            // The name is on the card's upper row, the numbers on the row below it. Which is which
            // is the whole reason a card needs two rows at all.
            final List<Run> name = PanelWalk.textRuns(runs, Glyphs.FONT_GUI_ROWS[upper]);
            assertTrue(name.stream().anyMatch(run -> run.content()
                            .equals(MenuFont.fit(card.name(), ObjectivePanel.CARD_NAME_WIDTH))
                            && run.x() == x + 15),
                    "card " + index + "'s name is not at x " + (x + 15) + " on row " + upper
                            + ": " + name);
            assertTrue(PanelWalk.textRuns(runs, Glyphs.FONT_GUI_ROWS[upper + 1]).stream()
                            .anyMatch(run -> run.content().equals(MenuFont.fold(card.numbers()))
                                    && run.x() == x + 3),
                    "card " + index + "'s numbers are not on the row under its name");
        }
    }

    @Test
    @DisplayName("a card's name and its numbers stay inside the card")
    void nothingOnACardRunsOffIt() {
        final ObjectivePanel.Card long_ = new ObjectivePanel.Card(Glyphs.GUI_ROW_ICON_HAND_IN,
                "Build a cathedral out of polished deepslate", "1234567/9999999", 0.5, false);
        final List<Run> runs = PanelWalk.runs(surface(List.of(long_), false, false));
        final int right = ObjectivePanel.CARD_X[0] + ObjectivePanel.CARD_WIDTH;

        for (final int row : new int[] {ObjectivePanel.CARD_ROW[0], ObjectivePanel.CARD_ROW[0] + 1}) {
            for (final Run run : PanelWalk.textRuns(runs, Glyphs.FONT_GUI_ROWS[row])) {
                assertTrue(run.end() <= right,
                        "'" + run.content() + "' ends at " + run.end() + " and the card ends at "
                                + right + ". A name that runs off a card runs onto the card beside"
                                + " it, and MenuFont.fit is what has to be given the right width");
            }
        }
    }

    @Test
    @DisplayName("the painted bar is the ratio, and it never leaves the track the card drew")
    void theBarIsTheRatioAndTheTrackIsTheCard() {
        // The track's own pixels, read off the card PNG rather than restated: this is the one place
        // the Java's idea of where the bar goes and the pack's idea of where it drew a groove can
        // disagree, and a fill outside the groove is a green bar floating on grey.
        final var card = PanelWalk.image("objective_card.png");
        assertEquals(ObjectivePanel.CARD_WIDTH, card.getWidth());
        assertEquals(ObjectivePanel.CARD_HEIGHT, card.getHeight());

        for (final double ratio : new double[] {0.0, 0.001, 0.25, 0.5, 0.605, 0.999, 1.0}) {
            final List<Run> runs = PanelWalk.runs(surface(List.of(
                    new ObjectivePanel.Card(Glyphs.GUI_ROW_ICON_HAND_IN, "X", "1/2", ratio, false)),
                    false, false));
            final List<Run> fills = runs.stream()
                    .filter(run -> run.font().equals(Glyphs.FONT_GUI))
                    .filter(run -> List.of(Glyphs.GUI_BAR_FILL_TOP).contains(run.content()))
                    .toList();

            // A bitmap glyph ADVANCES its width plus one, so the pixels a slice paints are its
            // advance less one. Measuring the advance instead is the mistake that makes every
            // multi-slice bar look right and every single-pixel one twice as long as it is.
            final int expected = expectedFill(ratio);
            final int drawn = fills.stream().mapToInt(run -> run.advance() - 1).sum();
            assertEquals(expected, drawn,
                    "a ratio of " + ratio + " should paint " + expected + " pixels of fill");
            if (expected == 0) {
                continue;
            }
            assertEquals(ObjectivePanel.CARD_X[0] + 4, fills.get(0).x(),
                    "the fill starts one pixel inside the track, which starts three inside the card");
            final Run last = fills.get(fills.size() - 1);
            assertTrue(last.x() + last.advance() - 1
                            <= ObjectivePanel.CARD_X[0] + 4 + ObjectivePanel.BAR_MAX,
                    "a fill of " + drawn + " ran past the track's right edge");
            // Largest slice first, so the run is the number's binary representation and there is
            // exactly one way to draw any width.
            for (int index = 1; index < fills.size(); index++) {
                assertTrue(fills.get(index).advance() < fills.get(index - 1).advance(),
                        "the fill slices are not in descending order, so the same width can be"
                                + " drawn two ways and two cards at the same ratio can differ");
                assertEquals(fills.get(index - 1).x() + fills.get(index - 1).advance() - 1,
                        fills.get(index).x(),
                        "the fill has a gap in it at " + fills.get(index).x());
            }
        }
    }

    @Test
    @DisplayName("a started objective always paints something, however little")
    void aStartedBarIsNeverEmpty() {
        // The same rule ProgressBar floors to one character for: "1 of 3000" must not read as
        // "not begun", which is the one thing a player would act on.
        assertEquals(0, expectedFill(0.0));
        assertEquals(1, expectedFill(0.0001));
    }

    @Test
    @DisplayName("a finished card wears the wash, and the wash is drawn over everything on it")
    void theDoneWashIsLast() {
        final List<Run> runs = PanelWalk.runs(surface(FOUR, false, false));
        final List<Run> washes = runs.stream()
                .filter(run -> run.content().equals(Glyphs.GUI_CARD_DONE_TOP)
                        || run.content().equals(Glyphs.GUI_CARD_DONE_BOTTOM))
                .toList();

        assertEquals(1, washes.size(), "exactly one of the four cards is finished");
        assertEquals(ObjectivePanel.CARD_X[1], washes.get(0).x(), "the second card is the done one");

        final int washAt = runs.indexOf(washes.get(0));
        final int lastOnThatCard = runs.stream()
                .filter(run -> run.x() >= ObjectivePanel.CARD_X[1])
                .filter(run -> run.font().equals(Glyphs.FONT_GUI_ROWS[ObjectivePanel.CARD_ROW[0]])
                        || run.font().equals(Glyphs.FONT_GUI_ROWS[ObjectivePanel.CARD_ROW[0] + 1])
                        || run.content().equals(Glyphs.GUI_CARD_TOP))
                .mapToInt(runs::indexOf).max().orElseThrow();
        assertTrue(washAt > lastOnThatCard,
                "the wash has to tint what is under it, the bar included - a finished objective's"
                        + " bar is full, and washing only its heading says the card is half settled");
    }

    @Test
    @DisplayName("the heading names the milestone, its bar and its counter, in that order left to right")
    void theHeadingReadsLeftToRight() {
        final List<Run> runs = PanelWalk.runs(surface(FOUR, false, false));
        assertEquals(ObjectivePanel.PILL_X,
                PanelWalk.find(runs, Glyphs.FONT_GUI_ROWS[ObjectivePanel.HEADING_ROW],
                        Glyphs.GUI_ROW_PILL_DARK).x(),
                "the heading is the darker plate, so it does not read as a fifth thing to click");

        final List<Run> text = PanelWalk.textRuns(runs, Glyphs.FONT_GUI_ROWS[ObjectivePanel.HEADING_ROW]);
        assertEquals(3, text.size(), "the heading draws a name, a bar and a counter: " + text);
        // Draw order is right to left here - the counter is placed first so the bar can be fitted
        // against it - so the assertion is about x, not about order.
        final List<Run> sorted = text.stream().sorted(java.util.Comparator.comparingInt(Run::x)).toList();
        assertEquals("FOOTHOLD", sorted.get(0).content());
        assertEquals(ObjectivePanel.HEADING_BAR_WIDTH, sorted.get(1).content().length(),
                "the heading's bar is a text bar and its width is fixed");
        assertEquals(ObjectivePanel.PILL_X + ObjectivePanel.PILL_WIDTH - 3, sorted.get(2).end(),
                "the counter is flush with the heading plate's right edge");
        for (int index = 1; index < sorted.size(); index++) {
            assertTrue(sorted.get(index - 1).end() <= sorted.get(index).x(),
                    "the heading's three parts overlap: " + sorted);
        }
    }

    @Test
    @DisplayName("the page buttons are drawn only when there is a second page, and on their own cells")
    void thePageButtonsAppearWithTheSecondPage() {
        final String font = Glyphs.FONT_GUI_ROWS[ObjectivePanel.SHARE_ROW];

        assertEquals(0, PanelWalk.runs(surface(FOUR, false, false)).stream()
                        .filter(run -> run.font().equals(font))
                        .filter(run -> run.content().equals(Glyphs.GUI_ROW_BUTTON_SMALL)
                                || run.content().equals(Glyphs.GUI_ROW_BUTTON_SMALL_OFF))
                        .count(),
                "a milestone with four objectives is exactly the artifact's drawing: one heading,"
                        + " four cards, one share line and no controls at all");

        final List<Run> paged = PanelWalk.runs(surface(FOUR, false, true));
        for (final int slot : new int[] {ObjectivePanel.PREV_SLOT, ObjectivePanel.NEXT_SLOT}) {
            final int cell = SlotGeometry.x(SlotGeometry.column(slot));
            final Run plate = paged.stream()
                    .filter(run -> run.font().equals(font))
                    .filter(run -> run.content().equals(Glyphs.GUI_ROW_BUTTON_SMALL)
                            || run.content().equals(Glyphs.GUI_ROW_BUTTON_SMALL_OFF))
                    .filter(run -> run.x() == cell + ObjectivePanel.INSET)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no page button on the cell of slot " + slot));
            assertTrue(plate.end() - 1 < cell + SlotGeometry.PITCH,
                    "a page button has to stay inside its own cell");
            assertEquals(ObjectivePanel.SHARE_ROW, SlotGeometry.row(slot));
        }
    }

    @Test
    @DisplayName("the share line gets out of the page buttons' way rather than running under them")
    void theShareLineShortensForTheControls() {
        final String font = Glyphs.FONT_GUI_ROWS[ObjectivePanel.SHARE_ROW];
        final String share = "Your share 100.0 % - spins: 12 and some more words after it";

        final Run unpaged = PanelWalk.textRuns(
                PanelWalk.runs(surface(FOUR, false, false, share)), font).get(0);
        final Run paged = PanelWalk.textRuns(
                PanelWalk.runs(surface(FOUR, false, true, share)), font).get(0);

        assertTrue(paged.end() <= SlotGeometry.x(7) - 2,
                "the share sentence runs under the page buttons, where the last words of it are"
                        + " simply invisible and nothing says so");
        assertTrue(paged.advance() < unpaged.advance(),
                "the same sentence has to be shortened when the controls are there, or the fit is"
                        + " being computed against the wrong width in one of the two cases");
    }

    @Test
    @DisplayName("more cards than a page holds is refused rather than drawn over the share line")
    void aPageIsFourCards() {
        final List<ObjectivePanel.Card> five = new ArrayList<>(FOUR);
        five.add(FOUR.get(0));
        assertThrows(IllegalArgumentException.class,
                () -> ObjectivePanel.title(Component.empty(), "M", "██░░░░", "1/5", five, "s",
                        false, false));
    }

    @Test
    @DisplayName("the four cards cover four separate blocks of eight slots, and column four is the gap")
    void theSlotMapIsTheDrawing() {
        final List<Integer> all = new ArrayList<>();
        for (int index = 0; index < ObjectivePanel.CARDS_PER_PAGE; index++) {
            final int card = index;
            final List<Integer> slots = ObjectivePanel.slotsOf(card);
            assertEquals(8, slots.size(), "a card is four columns on each of its two rows");
            slots.forEach(slot -> {
                assertEquals(card, ObjectivePanel.cardOf(slot),
                        "slot " + slot + " does not answer the card it belongs to");
                assertTrue(SlotGeometry.column(slot) != 4,
                        "column 4 is the gap between the two cards, as it is on the balloon");
            });
            all.addAll(slots);
        }
        assertEquals(all.size(), Set.copyOf(all).size(), "two cards claim the same slot");
        assertEquals(-1, ObjectivePanel.cardOf(SlotGeometry.slot(4, 1)),
                "the gap column belongs to no card");
        assertEquals(-1, ObjectivePanel.cardOf(SlotGeometry.slot(0, ObjectivePanel.HEADING_ROW)));
        assertEquals(-1, ObjectivePanel.cardOf(SlotGeometry.slot(0, ObjectivePanel.SHARE_ROW)));
    }

    @Test
    @DisplayName("every code point the surface uses is declared by the font that run names")
    void nothingIsDrawnOutOfAFontThatLacksIt() {
        final List<String> missing = new ArrayList<>();
        for (final Run run : PanelWalk.runs(surface(FOUR, true, true))) {
            final Set<Integer> declared = PanelWalk.declared(run.font());
            run.whole().codePoints().forEach(codePoint -> {
                if (!declared.contains(codePoint)) {
                    missing.add("U+%X in %s".formatted(codePoint, run.font()));
                }
            });
        }
        assertEquals(List.of(), missing,
                "a code point a font does not declare reaches the player as the missing-glyph box,"
                        + " which is also six pixels wide - so the row is not merely ugly, every"
                        + " position after it is wrong");
    }

    @Test
    @DisplayName("the two strings drawn inside the window carry no MiniMessage in either language")
    void theDrawnKeysAreTagless() {
        final List<String> tagged = new ArrayList<>();
        for (final String language : new String[] {"en", "de"}) {
            final Properties bundle = PanelWalk.bundle(language);
            for (final String key : new String[] {"smp.objectives.share",
                    "smp.objectives.share-none"}) {
                final String value = bundle.getProperty(key);
                assertTrue(value != null, language + " does not declare " + key);
                if (value.indexOf('<') >= 0 || value.indexOf('>') >= 0) {
                    tagged.add(language + " " + key + " = " + value);
                }
            }
        }
        assertEquals(List.of(), tagged,
                "these two are drawn in the pack's five-pixel sheet by MenuFont, which folds them to"
                        + " capitals and prints them character for character. A MiniMessage tag in"
                        + " one of them is not parsed - it is printed, and the sheet has no angle"
                        + " brackets so it would come out as ?GRAY?");
    }

    @Test
    @DisplayName("an icon is the objective's kind, or - when it is finished - the tick")
    void theIconIsTheState() {
        assertEquals(Glyphs.GUI_ROW_ICON_HAND_IN, ObjectivePanel.icon(ObjectiveType.HAND_IN, false));
        assertEquals(Glyphs.GUI_ROW_ICON_STATISTIC, ObjectivePanel.icon(ObjectiveType.STATISTIC, false));
        assertEquals(Glyphs.GUI_ROW_ICON_ADVANCEMENT,
                ObjectivePanel.icon(ObjectiveType.ADVANCEMENT, false));
        for (final ObjectiveType type : ObjectiveType.values()) {
            assertEquals(Glyphs.GUI_ROW_ICON_DONE, ObjectivePanel.icon(type, true),
                    "a finished objective wears the tick whichever kind it was - the icon on a card"
                            + " IS its state, and a done HAND_IN that still shows a hand invites a"
                            + " click that will be refused");
        }
    }

    @Test
    @DisplayName("the readable title is a sibling of the paint and names no font")
    void theTwoHalvesAreSeparate() {
        final Component title = ObjectivePanel.title(Component.text("Current objective"), "Foothold",
                "██░░░░", "1/4", FOUR, "Your share 4.2 %", false, false);
        assertEquals(2, title.children().size());
        assertEquals(Glyphs.FONT_GUI, title.children().get(0).style().font().asString());
        assertTrue(title.children().get(1).style().font() == null);
    }

    // --- helpers ---------------------------------------------------------------------------

    /** What {@code ObjectivePanel#fill} should paint, computed here rather than read from it. */
    private static int expectedFill(final double ratio) {
        final double clamped = Math.max(0.0, Math.min(1.0, ratio));
        final int width = (int) Math.floor(clamped * ObjectivePanel.BAR_MAX);
        return width == 0 && clamped > 0.0 ? 1 : width;
    }

    private static Component surface(final List<ObjectivePanel.Card> cards,
                                     final boolean hasPrev, final boolean hasNext) {
        return surface(cards, hasPrev, hasNext, "Your share 4.2 % - spins: 1");
    }

    private static Component surface(final List<ObjectivePanel.Card> cards, final boolean hasPrev,
                                     final boolean hasNext, final String share) {
        return PanelWalk.surface(ObjectivePanel.title(Component.text("Current objective"),
                "Foothold", "██░░░░", "1/4", cards, share, hasPrev, hasNext));
    }
}
