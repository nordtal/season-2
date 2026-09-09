package eu.nordtal.s2.smp.wheel;

import eu.nordtal.s2.common.Glyphs;
import eu.nordtal.s2.common.menu.MenuTitle;
import eu.nordtal.s2.common.menu.SlotGeometry;
import eu.nordtal.s2.smp.menu.PanelWalk;
import eu.nordtal.s2.smp.menu.PanelWalk.Run;

import net.kyori.adventure.text.Component;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Walks the wheel's composed window and holds it against the ring the pack drew.
 *
 * <p>The two that carry the most are {@link #theRingIsWhereTheItemsAre} - the panel is baked and the
 * slot map is Java, so a cell painted in one place and filled in another is a prize icon sitting on
 * bare panel with a hole in the ring beside it - and {@link #theRingHasMovedTwoColumnsLeft}, which
 * is the whole of what the owner asked for and the reason the controls have anywhere to be.</p>
 */
class WheelPanelTest {

    @Test
    @DisplayName("the whole surface returns the cursor to the title anchor")
    void theReadableTitleStillLandsWhereItWould() {
        final List<Run> runs = PanelWalk.runs(surface());
        assertEquals(MenuTitle.ANCHOR_X, runs.get(runs.size() - 1).end());
    }

    @Test
    @DisplayName("the ring's twelve cells are twelve distinct slots on five rows")
    void theRingIsTwelveCells() {
        assertEquals(12, WheelPanel.CELL_SLOTS.size());
        assertEquals(12, Set.copyOf(WheelPanel.CELL_SLOTS).size(), "two cells claim one slot");
        assertEquals(new WheelStrip.Shape(12, 0), WheelPanel.shape(),
                "the strip has to be built for the surface it is drawn on, and the winner rests on"
                        + " the cell the baked frame marks - which is the first");

        for (final int slot : WheelPanel.CELL_SLOTS) {
            assertTrue(SlotGeometry.row(slot) < WheelPanel.ROWS, "slot " + slot + " is off the window");
        }
        assertTrue(!WheelPanel.CELL_SLOTS.contains(WheelPanel.HUB_SLOT),
                "the hub is inside the ring, so it cannot also be a prize cell");
        WheelPanel.AGAIN_SLOTS.forEach(slot ->
                assertTrue(!WheelPanel.CELL_SLOTS.contains(slot),
                        "the button is on a cell a prize also lands in, so a click during the"
                                + " animation would be a click on a moving icon"));
        WheelPanel.INFO_SLOTS.forEach(slot ->
                assertTrue(!WheelPanel.CELL_SLOTS.contains(slot)));
    }

    @Test
    @DisplayName("the ring has moved two slot columns left, which is what frees the controls")
    void theRingHasMovedTwoColumnsLeft() {
        // W3 draws the ring on columns 2..6 around x 88. The owner moved it two columns left on
        // 2026-09-08 so that columns 5..8 carry the button and the two things a player needs to
        // know; this is that instruction, as an assertion.
        final Set<Integer> columns = new LinkedHashSet<>();
        WheelPanel.CELL_SLOTS.forEach(slot -> columns.add(SlotGeometry.column(slot)));
        assertEquals(Set.of(0, 1, 2, 3, 4), columns,
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
    @DisplayName("every cell the Java fills is a cell the panel painted, and the other way round")
    void theRingIsWhereTheItemsAre() {
        // The ring is baked art and the slot map is Java, and nothing else compares the two. A cell
        // painted at one slot and filled at another is a prize icon on bare panel with a hole in
        // the ring beside it - and it draws perfectly, on every frame.
        final BufferedImage ring = PanelWalk.image("wheel_ring.png");
        assertEquals(176, ring.getWidth());
        assertEquals(114 + 18 * WheelPanel.ROWS, ring.getHeight());

        final Set<Integer> painted = new LinkedHashSet<>();
        for (int row = 0; row < WheelPanel.ROWS; row++) {
            for (int column = 0; column < SlotGeometry.COLUMNS; column++) {
                // The backing is the 16 x 16 the item is drawn in, so its own middle is the honest
                // place to sample: the ring band runs right up to the cell's edge.
                final int rgb = ring.getRGB(SlotGeometry.x(column) + 9, SlotGeometry.y(row) + 9);
                if (rgb == CELL || rgb == WINNER) {
                    painted.add(SlotGeometry.slot(column, row));
                }
            }
        }
        assertEquals(Set.copyOf(WheelPanel.CELL_SLOTS), painted,
                "the cells the panel paints and the slots the animation writes into are different"
                        + " sets");
    }

    @Test
    @DisplayName("the resting cell is the only one the panel marks, and it is marked twice over")
    void theWinnerCellIsMarked() {
        final BufferedImage ring = PanelWalk.image("wheel_ring.png");
        final int resting = WheelPanel.CELL_SLOTS.get(WheelPanel.shape().centre());
        final int x = SlotGeometry.x(SlotGeometry.column(resting));
        final int y = SlotGeometry.y(SlotGeometry.row(resting));

        assertEquals(WINNER, ring.getRGB(x + 9, y + 9),
                "the resting cell wears a lighter backing than the other eleven");
        int lighter = 0;
        for (final int slot : WheelPanel.CELL_SLOTS) {
            if (ring.getRGB(SlotGeometry.x(SlotGeometry.column(slot)) + 9,
                    SlotGeometry.y(SlotGeometry.row(slot)) + 9) == WINNER) {
                lighter++;
            }
        }
        assertEquals(1, lighter, "more than one cell says the winner stops in it");

        // ...and the frame, which is what replaced W3's pointer. The design draws a triangle at
        // y 13-16 - inside the title bar - which works at x 85 and does not at x 49, where the
        // window's own title is. The frame is the pack's own word for "this one" and lands nowhere
        // near the title.
        assertEquals(FRAME, ring.getRGB(x, y) & 0xFFFFFF,
                "the resting cell has no frame on it, so the only cue that the wheel stops there is"
                        + " a slightly lighter grey");
        assertEquals(FRAME, ring.getRGB(x + 17, y + 17) & 0xFFFFFF);
    }

    @Test
    @DisplayName("the hub is the middle of the ring and carries the number")
    void theHubIsTheMiddle() {
        assertEquals(2, SlotGeometry.column(WheelPanel.HUB_SLOT));
        assertEquals(2, SlotGeometry.row(WheelPanel.HUB_SLOT));

        final List<Run> text = PanelWalk.textRuns(PanelWalk.runs(surface()), Glyphs.FONT_GUI_ROWS[2]);
        final Run number = text.stream().filter(run -> run.content().equals("7")).findFirst()
                .orElseThrow(() -> new AssertionError("the hub does not draw the spin count: " + text));
        final int middle = SlotGeometry.x(2) + SlotGeometry.PITCH / 2;
        assertTrue(Math.abs((number.x() + number.end()) / 2 - middle) <= 1,
                "the hub's number is not centred on the hub: it runs " + number.x() + ".."
                        + number.end() + " and the hub's middle is " + middle);
    }

    @Test
    @DisplayName("the button sits on the cells that carry its click, and its label is centred")
    void theButtonIsItsSlots() {
        final List<Run> runs = PanelWalk.runs(surface());
        final String font = Glyphs.FONT_GUI_ROWS[WheelPanel.AGAIN_ROW];
        final Run plate = PanelWalk.find(runs, font, Glyphs.GUI_ROW_BUTTON_CONFIRM);

        final int first = SlotGeometry.x(SlotGeometry.column(WheelPanel.AGAIN_SLOTS.get(0)));
        assertEquals(first + WheelPanel.INSET, plate.x());
        assertTrue(plate.end() - 1
                        <= SlotGeometry.x(SlotGeometry.column(WheelPanel.AGAIN_SLOTS.get(2)))
                        + SlotGeometry.PITCH,
                "the plate runs past the last cell that carries its click");

        final Run label = PanelWalk.textRuns(runs, font).get(0);
        final int leftGap = label.x() - WheelPanel.AGAIN_X;
        final int rightGap = WheelPanel.AGAIN_X + WheelPanel.AGAIN_WIDTH - label.end();
        assertTrue(leftGap >= 0 && rightGap >= 0, "the label runs off its own plate");
        assertTrue(Math.abs(leftGap - rightGap) <= 1, "the label is not centred on the plate");
    }

    @Test
    @DisplayName("every drawn line stays inside the four columns the ring's move freed")
    void nothingWrittenBesideTheRingTouchesIt() {
        final List<Run> runs = PanelWalk.runs(surface());
        final int left = SlotGeometry.x(5) + WheelPanel.INSET;
        final int right = SlotGeometry.x(8) + SlotGeometry.PITCH - WheelPanel.INSET;

        for (final int row : new int[] {1, 3, WheelPanel.AGAIN_ROW}) {
            for (final Run run : PanelWalk.textRuns(runs, Glyphs.FONT_GUI_ROWS[row])) {
                assertTrue(run.x() >= left,
                        "'" + run.content() + "' starts at " + run.x() + ", which is over the ring");
                assertTrue(run.end() <= right,
                        "'" + run.content() + "' ends at " + run.end() + " and the window's slot"
                                + " area ends at " + right);
            }
        }
    }

    @Test
    @DisplayName("every info and button cell is claimed, because a free one is where a lost item lands")
    void everyControlCellIsClaimed() {
        final Set<Integer> claimed = new LinkedHashSet<>(WheelPanel.INFO_SLOTS);
        claimed.addAll(WheelPanel.AGAIN_SLOTS);
        assertEquals(WheelPanel.INFO_SLOTS.size() + WheelPanel.AGAIN_SLOTS.size(), claimed.size(),
                "a slot is claimed twice");
        // Rows 0 and 2 of columns 5..8 are the two that are deliberately not written on; the hub's
        // own tooltip covers 5..8 of rows 1..3 and the button 6..8 of row 4, so a shift-click can
        // still land on row 0. Every click in this window is cancelled, which is what makes that
        // safe here and not in the grave.
        assertTrue(claimed.size() >= 12);
    }

    @Test
    @DisplayName("every code point the surface uses is declared by the font that run names")
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
    @DisplayName("the four strings drawn inside the window carry no MiniMessage in either language")
    void theDrawnKeysAreTagless() {
        final List<String> tagged = new ArrayList<>();
        for (final String language : new String[] {"en", "de"}) {
            final Properties bundle = PanelWalk.bundle(language);
            for (final String key : new String[] {"smp.wheel.spins-left", "smp.wheel.rule-top",
                    "smp.wheel.rule-bottom", "smp.wheel.again-button"}) {
                final String value = bundle.getProperty(key);
                assertTrue(value != null, language + " does not declare " + key);
                if (value.indexOf('<') >= 0 || value.indexOf('>') >= 0) {
                    tagged.add(language + " " + key + " = " + value);
                }
            }
        }
        assertEquals(List.of(), tagged,
                "these four are drawn in the pack's five-pixel sheet by MenuFont, which prints them"
                        + " character for character - a tag would reach the player as literal text");
    }

    // The two backing colours the generator paints a cell in, as ARGB.
    private static final int CELL = 0xFF3A3A40;
    private static final int WINNER = 0xFF48484F;
    private static final int FRAME = 0xFFFFFF;

    private static Component surface() {
        return PanelWalk.surface(WheelPanel.title(Component.text("Wheel"), "7", "7 spins left",
                "One spin per", "2 % share", "Again"));
    }
}
