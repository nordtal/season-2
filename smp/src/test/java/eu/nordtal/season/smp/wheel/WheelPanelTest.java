package eu.nordtal.season.smp.wheel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.messagerendering.MenuDocument;
import eu.nordtal.season.packrendering.Glyphs;
import eu.nordtal.season.papercommon.menu.MenuFont;
import eu.nordtal.season.papercommon.menu.MenuTitle;
import eu.nordtal.season.papercommon.menu.SlotGeometry;
import eu.nordtal.season.smp.menu.PanelWalk;
import eu.nordtal.season.smp.menu.PanelWalk.Run;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

class WheelPanelTest {

    @Test
    void theReadableTitleStillLandsWhereItWould() {
        final List<Run> runs = PanelWalk.runs(surface());
        assertEquals(MenuTitle.ANCHOR_X, runs.get(runs.size() - 1).end());
    }

    @Test
    void theRingIsTwelveCells() {
        assertEquals(12, WheelPanel.CELL_SLOTS.size());
        assertEquals(12, Set.copyOf(WheelPanel.CELL_SLOTS).size(), "two cells claim one slot");
        assertEquals(
                new WheelStrip.Shape(12, 0),
                WheelPanel.shape(),
                "the strip has to be built for the surface it is drawn on, and the winner rests on"
                        + " the cell the baked frame marks - which is the first");

        for (final int slot : WheelPanel.CELL_SLOTS) {
            assertTrue(SlotGeometry.row(slot) < WheelPanel.ROWS, "slot " + slot + " is off the window");
        }
        assertTrue(
                !WheelPanel.CELL_SLOTS.contains(WheelPanel.HUB_SLOT),
                "the hub is inside the ring, so it cannot also be a prize cell");
        WheelPanel.AGAIN_SLOTS.forEach(slot -> assertTrue(
                !WheelPanel.CELL_SLOTS.contains(slot),
                "the button is on a cell a prize also lands in, so a click during the"
                        + " animation would be a click on a moving icon"));
        WheelPanel.INFO_SLOTS.forEach(slot -> assertTrue(!WheelPanel.CELL_SLOTS.contains(slot)));
    }

    @Test
    void theRingHasMovedTwoColumnsLeft() {
        // The ring covers columns 0 to 4 of the grid.
        final Set<Integer> columns = new LinkedHashSet<>();
        WheelPanel.CELL_SLOTS.forEach(slot -> columns.add(SlotGeometry.column(slot)));
        assertEquals(
                Set.of(0, 1, 2, 3, 4),
                columns,
                "the ring has to sit on columns 0 to 4. Anything further right and the controls"
                        + " have nowhere to be, which is the whole reason it was moved");

        for (final int slot : WheelPanel.AGAIN_SLOTS) {
            assertTrue(SlotGeometry.column(slot) >= 5, "the button is over the ring");
        }
        for (final int slot : WheelPanel.INFO_SLOTS) {
            assertTrue(SlotGeometry.column(slot) >= 5, "an info line is over the ring");
        }
    }

    @Test
    void theRingIsWhereTheItemsAre() {
        // The ring is a pack image and the slot map is Java code, so a mismatch draws a prize icon outside the ring.
        final BufferedImage ring = PanelWalk.image("wheel_ring.png");
        assertEquals(176, ring.getWidth());
        assertEquals(114 + 18 * WheelPanel.ROWS, ring.getHeight());

        final Set<Integer> painted = new LinkedHashSet<>();
        for (int row = 0; row < WheelPanel.ROWS; row++) {
            for (int column = 0; column < SlotGeometry.COLUMNS; column++) {
                final int rgb = ring.getRGB(SlotGeometry.x(column) + 9, SlotGeometry.y(row) + 9);
                if (rgb == CELL || rgb == WINNER) {
                    painted.add(SlotGeometry.slot(column, row));
                }
            }
        }
        assertEquals(
                Set.copyOf(WheelPanel.CELL_SLOTS),
                painted,
                "the cells the panel paints and the slots the animation writes into are different" + " sets");
    }

    @Test
    void theWinnerCellIsMarked() {
        final BufferedImage ring = PanelWalk.image("wheel_ring.png");
        final int resting = WheelPanel.CELL_SLOTS.get(WheelPanel.shape().centre());
        final int x = SlotGeometry.x(SlotGeometry.column(resting));
        final int y = SlotGeometry.y(SlotGeometry.row(resting));

        assertEquals(
                WINNER, ring.getRGB(x + 9, y + 9), "the resting cell wears a lighter backing than the other eleven");
        int lighter = 0;
        for (final int slot : WheelPanel.CELL_SLOTS) {
            if (ring.getRGB(SlotGeometry.x(SlotGeometry.column(slot)) + 9, SlotGeometry.y(SlotGeometry.row(slot)) + 9)
                    == WINNER) {
                lighter++;
            }
        }
        assertEquals(1, lighter, "more than one cell says the winner stops in it");

        assertEquals(
                FRAME,
                ring.getRGB(x, y) & 0xFFFFFF,
                "the resting cell has no frame on it, so the only cue that the wheel stops there is"
                        + " a slightly lighter grey");
        assertEquals(FRAME, ring.getRGB(x + 17, y + 17) & 0xFFFFFF);
    }

    @Test
    void theHubIsTheMiddle() {
        assertEquals(2, SlotGeometry.column(WheelPanel.HUB_SLOT));
        assertEquals(3, SlotGeometry.row(WheelPanel.HUB_SLOT));

        final List<Run> text = PanelWalk.textRuns(PanelWalk.runs(surface()), Glyphs.FONT_GUI_ROWS.get(3));
        final Run number = text.stream()
                .filter(run -> run.content().equals("7"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the hub does not draw the spin count: " + text));
        final int middle = SlotGeometry.x(2) + SlotGeometry.PITCH / 2;
        assertTrue(
                Math.abs((number.x() + number.end()) / 2 - middle) <= 1,
                "the hub's number is not centred on the hub: it runs " + number.x() + ".." + number.end()
                        + " and the hub's middle is " + middle);
    }

    @Test
    void theButtonIsItsSlots() {
        final List<Run> runs = PanelWalk.runs(surface());
        final String font = Glyphs.FONT_GUI_ROWS.get(WheelPanel.AGAIN_ROW);
        final Run plate = PanelWalk.find(runs, font, Glyphs.GUI_ROW_BUTTON_CONFIRM);

        final int first = SlotGeometry.x(SlotGeometry.column(WheelPanel.AGAIN_SLOTS.get(0)));
        final int last = SlotGeometry.column(WheelPanel.AGAIN_SLOTS.get(WheelPanel.AGAIN_SLOTS.size() - 1));
        assertEquals(WheelPanel.AGAIN_X, plate.x());
        assertTrue(plate.x() >= first, "the plate starts before the first cell that carries its click");
        assertTrue(
                plate.end() - 1 <= SlotGeometry.x(last) + SlotGeometry.PITCH,
                "the plate runs past the last cell that carries its click");

        final Run label = PanelWalk.textRuns(runs, font).get(0);
        final int leftGap = label.x() - WheelPanel.AGAIN_X;
        final int rightGap = WheelPanel.AGAIN_X + WheelPanel.AGAIN_WIDTH - label.end();
        assertTrue(leftGap >= 0 && rightGap >= 0, "the label runs off its own plate");
        assertTrue(Math.abs(leftGap - rightGap) <= 1, "the label is not centred on the plate");
    }

    @Test
    void nothingWrittenBesideTheRingTouchesIt() {
        final List<Run> runs = PanelWalk.runs(surface());
        final int left = SlotGeometry.x(5) + WheelPanel.INSET;
        final int right = SlotGeometry.x(8) + SlotGeometry.PITCH - WheelPanel.INSET;

        for (final int row : new int[] {2, 4, WheelPanel.AGAIN_ROW}) {
            for (final Run run : PanelWalk.textRuns(runs, Glyphs.FONT_GUI_ROWS.get(row))) {
                assertTrue(
                        run.x() >= left, "'" + run.content() + "' starts at " + run.x() + ", which is over the ring");
                assertTrue(
                        run.end() <= right,
                        "'" + run.content() + "' ends at " + run.end() + " and the window's slot" + " area ends at "
                                + right);
            }
        }
    }

    @Test
    void everyControlCellIsClaimed() {
        final Set<Integer> claimed = new LinkedHashSet<>(WheelPanel.INFO_SLOTS);
        claimed.addAll(WheelPanel.AGAIN_SLOTS);
        assertEquals(
                WheelPanel.INFO_SLOTS.size() + WheelPanel.AGAIN_SLOTS.size(),
                claimed.size(),
                "a slot is claimed twice");
        assertTrue(claimed.size() >= 12);
    }

    @Test
    void nothingIsDrawnOutOfAFontThatLacksIt() {
        final List<String> missing = new ArrayList<>();
        for (final Run run : PanelWalk.runs(surface())) {
            final Set<Integer> declared = PanelWalk.declared(run.font());
            run.whole().codePoints().forEach(codePoint -> {
                if (!declared.contains(codePoint)) {
                    missing.add("U+%X in %s".formatted(codePoint, run.font()));
                }
            });
        }
        assertEquals(List.of(), missing);
    }

    @Test
    void theWheelPromisesNoSpinForAShare() {
        final List<String> promising = new ArrayList<>();
        for (final String language : new String[] {"en", "de"}) {
            final MenuDocument document = wheel(language);
            final List<String> lines = new ArrayList<>(PanelWalk.lines(document.item("hub")));
            lines.add(document.label("rule-top"));
            lines.add(document.label("rule-bottom"));
            for (final String line : lines) {
                if (line.indexOf('%') >= 0) {
                    promising.add(language + " " + line);
                }
            }
        }
        assertEquals(
                List.of(),
                promising,
                "an objective splits its spin budget by contribution, so no share is a promise of a spin;"
                        + " the wheel says the more you put in, the more you get");
    }

    @Test
    void theRealRuleLinesNeverNeedTruncation() {
        for (final String language : new String[] {"en", "de"}) {
            final MenuDocument document = wheel(language);
            final String top = document.label("rule-top");
            final String bottom = document.label("rule-bottom");
            final List<Run> runs = PanelWalk.runs(PanelWalk.surface(
                    WheelPanel.title(Component.text("Wheel"), "7", "7 spins left", top, bottom, "Again")));
            for (final String line : new String[] {top, bottom}) {
                assertTrue(
                        runs.stream().anyMatch(run -> run.content().equals(MenuFont.fold(line))),
                        language + " \"" + line + "\" is shortened beside the ring");
            }
        }
    }

    @Test
    void thePointerClearsTheTitleSeparator() {
        final BufferedImage ring = PanelWalk.image("wheel_ring.png");
        final int separator = SlotGeometry.ORIGIN_Y - 1;
        for (int x = SlotGeometry.x(0); x < SlotGeometry.x(SlotGeometry.COLUMNS); x++) {
            assertEquals(SEPARATOR, ring.getRGB(x, separator), "the separator under the title is broken at x " + x);
        }

        final int resting = WheelPanel.CELL_SLOTS.get(WheelPanel.shape().centre());
        final int column = SlotGeometry.column(resting);
        assertTrue(SlotGeometry.row(resting) >= 1, "the resting cell leaves no row for the pointer above it");
        boolean pointer = false;
        for (int y = separator + 2; y < SlotGeometry.y(SlotGeometry.row(resting)) - 1; y++) {
            pointer |= ring.getRGB(SlotGeometry.x(column) + 9, y) == POINTER;
        }
        assertTrue(pointer, "no pointer between the separator and the resting cell");
    }

    @Test
    void theTextsBesideTheRingAreCentredInTheirArea() {
        final List<Run> runs = PanelWalk.runs(surface());
        final int middle = (SlotGeometry.x(5) + SlotGeometry.x(SlotGeometry.COLUMNS)) / 2;
        for (final String text : new String[] {"7 spins left", "One spin per", "2 % share"}) {
            final Run run = runs.stream()
                    .filter(candidate -> candidate.content().equals(MenuFont.fold(text)))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("'" + text + "' is not drawn"));
            assertTrue(
                    Math.abs((run.x() + run.end()) / 2 - middle) <= 1,
                    "'" + text + "' runs " + run.x() + ".." + run.end() + " and the area's middle is " + middle);
        }

        final Run plate =
                PanelWalk.find(runs, Glyphs.FONT_GUI_ROWS.get(WheelPanel.AGAIN_ROW), Glyphs.GUI_ROW_BUTTON_CONFIRM);
        assertTrue(
                Math.abs((plate.x() + plate.end()) / 2 - middle) <= 1,
                "the button runs " + plate.x() + ".." + plate.end() + " and the area's middle is " + middle);
        assertEquals(WheelPanel.ROWS - 1, WheelPanel.AGAIN_ROW, "the button leaves the bottom row");
    }

    private static final int SEPARATOR = 0xFF96969C;
    private static final int POINTER = 0xFF232328;
    private static final int CELL = 0xFF3A3A40;
    private static final int WINNER = 0xFF48484F;
    private static final int FRAME = 0xFFFFFF;

    private static MenuDocument wheel(final String language) {
        return PanelWalk.document(WheelGui.DOCUMENT, Locale.forLanguageTag(language), new WheelGui.Window(7));
    }

    private static Component surface() {
        return PanelWalk.surface(
                WheelPanel.title(Component.text("Wheel"), "7", "7 spins left", "One spin per", "2 % share", "Again"));
    }
}
