package eu.nordtal.s2.smp.navigate;

import eu.nordtal.s2.common.Glyphs;
import eu.nordtal.s2.common.menu.MenuFont;
import eu.nordtal.s2.common.menu.MenuTitle;
import eu.nordtal.s2.common.menu.SlotGeometry;
import eu.nordtal.s2.smp.menu.PanelWalk;
import eu.nordtal.s2.smp.menu.PanelWalk.Run;

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
 * Walks {@code /navigate}'s composed window with the pack's own advances.
 *
 * <p>The cursor walk itself is {@link PanelWalk}, shared with the other panels' tests since
 * 2026-09-09 - see its own comment for why the walk rather than the intent is what gets asserted.
 * What is here is only what is true of <em>this</em> menu.</p>
 */
class NavigatePanelTest {

    private static final List<NavigatePanel.Entry> THREE = List.of(
            new NavigatePanel.Entry(Glyphs.GUI_ROW_ICON_SPAWN, "This world's spawn", "12 m", false),
            new NavigatePanel.Entry(Glyphs.GUI_ROW_ICON_DEATH, "Where you last died", "1240 m", true),
            new NavigatePanel.Entry(Glyphs.GUI_ROW_ICON_POI, "Baeckerei am Fluss", "318 m", false));

    @Test
    @DisplayName("the whole surface returns the cursor to the title anchor")
    void theReadableTitleStillLandsWhereItWould() {
        final List<Run> runs = PanelWalk.runs(surface(THREE, true, true));
        final Run last = runs.get(runs.size() - 1);
        assertEquals(MenuTitle.ANCHOR_X, last.end(),
                "the panel, five rows of furniture and the control row have to add up to nothing."
                        + " They do not merely move the readable title if they do not - every"
                        + " label after them is off by the same amount, and nothing fails");
    }

    @Test
    @DisplayName("every row's pill, icon and label land on the x the panel says")
    void everyRowIsWhereItSaysItIs() {
        final List<Run> runs = PanelWalk.runs(surface(THREE, true, true));

        assertEquals(0, PanelWalk.find(runs, Glyphs.FONT_GUI, Glyphs.GUI_PANEL_PLAIN_6).x(),
                "the panel has to start on the window's left edge");

        for (int row = 0; row < THREE.size(); row++) {
            final String font = Glyphs.FONT_GUI_ROWS[row];
            assertEquals(NavigatePanel.PILL_X, PanelWalk.find(runs, font, Glyphs.GUI_ROW_PILL).x(),
                    "row " + row + "'s pill");
            assertEquals(NavigatePanel.PILL_X + 3, PanelWalk.find(runs, font, THREE.get(row).icon()).x(),
                    "row " + row + "'s icon");

            final List<Run> text = PanelWalk.textRuns(runs, font);
            assertEquals(2, text.size(), "row " + row + " should draw a name and a distance");
            assertEquals(MenuFont.fold(THREE.get(row).distance()), text.get(1).content(),
                    "the second thing written on a row is its distance");
            assertEquals(NavigatePanel.PILL_X + NavigatePanel.PILL_WIDTH - 3, text.get(1).end(),
                    "row " + row + "'s distance is not flush with the pill's right edge");
            assertTrue(text.get(0).end() <= text.get(1).x(),
                    "row " + row + "'s name runs into its distance: '" + text.get(0).content()
                            + "' ends at " + text.get(0).end()
                            + " and the distance starts at " + text.get(1).x());
        }
    }

    @Test
    @DisplayName("only the active entry wears the frame, and it is drawn after everything on its row")
    void theFrameMarksOneRowAndIsDrawnLast() {
        final List<Run> runs = PanelWalk.runs(surface(THREE, true, true));
        final List<Run> frames = runs.stream()
                .filter(run -> run.content().equals(Glyphs.GUI_ROW_FRAME))
                .toList();

        assertEquals(1, frames.size(), "exactly one entry is the one being navigated to");
        assertEquals(Glyphs.FONT_GUI_ROWS[1], frames.get(0).font(), "the second entry is the active one");
        assertEquals(NavigatePanel.PILL_X, frames.get(0).x());

        final int frameAt = runs.indexOf(frames.get(0));
        final int lastOfRow = runs.stream().filter(run -> run.font().equals(Glyphs.FONT_GUI_ROWS[1]))
                .mapToInt(runs::indexOf).max().orElseThrow();
        assertEquals(lastOfRow, frameAt,
                "the frame is two pixels of white laid on the pill's own edge, so it has to be the"
                        + " last thing drawn on its row - a label composed after it would cross it");
    }

    @Test
    @DisplayName("a pill covers exactly the nine slot cells of its row, inset two")
    void aPillIsItsRow() {
        assertEquals(SlotGeometry.x(0) + NavigatePanel.INSET, NavigatePanel.PILL_X);
        assertEquals(SlotGeometry.x(8) + SlotGeometry.PITCH - 1 - NavigatePanel.INSET,
                NavigatePanel.PILL_X + NavigatePanel.PILL_WIDTH - 1,
                "the pill has to end two pixels inside the ninth slot cell, or a click at the far"
                        + " right of the row is a click on a slot with no paint over it");
    }

    @Test
    @DisplayName("every control's plate sits inside the slot cell that carries its click")
    void theControlsSitOnTheirSlots() {
        final List<Run> runs = PanelWalk.runs(surface(THREE, true, true));
        final String font = Glyphs.FONT_GUI_ROWS[NavigatePanel.CONTROL_ROW];

        final Run stop = PanelWalk.find(runs, font, Glyphs.GUI_ROW_BUTTON_WIDE);
        assertEquals(SlotGeometry.x(SlotGeometry.column(NavigatePanel.STOP_SLOTS.get(0)))
                + NavigatePanel.INSET, stop.x());
        assertTrue(stop.end() - 1
                        <= SlotGeometry.x(SlotGeometry.column(NavigatePanel.STOP_SLOTS.get(2)))
                        + SlotGeometry.PITCH,
                "the stop plate runs past the last cell that carries its click, so part of it is"
                        + " painted over a slot that does nothing");

        for (final int slot : new int[] {NavigatePanel.PREV_SLOT, NavigatePanel.NEXT_SLOT}) {
            final int cell = SlotGeometry.x(SlotGeometry.column(slot));
            final Run plate = runs.stream()
                    .filter(run -> run.font().equals(font))
                    .filter(run -> run.content().equals(Glyphs.GUI_ROW_BUTTON_SMALL)
                            || run.content().equals(Glyphs.GUI_ROW_BUTTON_SMALL_OFF))
                    .filter(run -> run.x() == cell + NavigatePanel.INSET)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no page button on the cell of slot " + slot));
            assertTrue(plate.end() - 1 < cell + SlotGeometry.PITCH,
                    "a page button has to stay inside its own cell");
        }
        assertEquals(NavigatePanel.CONTROL_ROW, SlotGeometry.row(NavigatePanel.PAGE_SLOT));
    }

    @Test
    @DisplayName("a page button with no page behind it is drawn greyed rather than left off")
    void aDeadPageButtonIsStillDrawn() {
        final List<Run> runs = PanelWalk.runs(surface(THREE, false, false));
        final String font = Glyphs.FONT_GUI_ROWS[NavigatePanel.CONTROL_ROW];
        assertEquals(2, runs.stream().filter(run -> run.font().equals(font))
                        .filter(run -> run.content().equals(Glyphs.GUI_ROW_BUTTON_SMALL_OFF)).count(),
                "on a single-page list both page buttons are greyed and both are still there - a"
                        + " control that vanishes leaves a player wondering whether it was ever"
                        + " there, and the click on a greyed one is refused with a sound");
        assertEquals(0, runs.stream().filter(run -> run.content().equals(Glyphs.GUI_ROW_BUTTON_SMALL))
                .count());
    }

    @Test
    @DisplayName("every code point the surface uses is declared by the font that run names")
    void nothingIsDrawnOutOfAFontThatLacksIt() {
        final List<String> missing = new ArrayList<>();
        for (final Run run : PanelWalk.runs(surface(THREE, true, true))) {
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
    @DisplayName("more entries than a page holds is refused rather than drawn over the controls")
    void aPageIsFiveEntries() {
        final List<NavigatePanel.Entry> six = new ArrayList<>(THREE);
        six.addAll(THREE);
        assertThrows(IllegalArgumentException.class,
                () -> NavigatePanel.title(Component.empty(), six, "Stop", "1/1", false, false));
    }

    @Test
    @DisplayName("the four strings drawn inside the window carry no MiniMessage in either language")
    void theRowKeysAreTagless() {
        final List<String> tagged = new ArrayList<>();
        for (final String language : new String[] {"en", "de"}) {
            final Properties bundle = PanelWalk.bundle(language);
            for (final String key : new String[] {"smp.navigate.stop-button", "smp.navigate.distance",
                    "smp.navigate.other-world", "smp.navigate.page"}) {
                final String value = bundle.getProperty(key);
                assertTrue(value != null, language + " does not declare " + key);
                if (value.indexOf('<') >= 0 || value.indexOf('>') >= 0) {
                    tagged.add(language + " " + key + " = " + value);
                }
            }
        }
        assertEquals(List.of(), tagged,
                "these four are drawn in the pack's five-pixel sheet by MenuFont, which folds them"
                        + " to capitals and prints them character for character. A MiniMessage tag"
                        + " in one of them is not parsed - it is printed, as <GRAY>, and the sheet"
                        + " has no angle brackets so it would come out as ?GRAY?");
    }

    @Test
    @DisplayName("the readable title is a sibling of the paint and names no font")
    void theTwoHalvesAreSeparate() {
        final Component title = NavigatePanel.title(Component.text("Navigate"), THREE, "Stop",
                "2/3", true, true);
        assertEquals(2, title.children().size());
        assertEquals(Glyphs.FONT_GUI, title.children().get(0).style().font().asString(),
                "the paint has to name nordtal:gui, or its code points resolve in whatever"
                        + " minecraft:default happens to hold at the same numbers");
        assertTrue(title.children().get(1).style().font() == null,
                "the readable title renders in minecraft:default, which is where the letters are -"
                        + " nordtal:gui carries no ascii sheet at all");
    }

    @Test
    @DisplayName("the pack draws each plate at the width the panel places it at")
    void theArtIsTheWidthTheJavaAssumes() {
        assertEquals(NavigatePanel.PILL_WIDTH, PanelWalk.image("row_pill.png").getWidth());
        assertEquals(NavigatePanel.PILL_WIDTH, PanelWalk.image("row_frame.png").getWidth());
        assertEquals(SlotGeometry.PITCH - 2 * NavigatePanel.INSET,
                PanelWalk.image("row_button_small.png").getWidth());
        assertEquals(SlotGeometry.PITCH - 2 * NavigatePanel.INSET,
                PanelWalk.image("row_button_small_off.png").getWidth());
        for (final String plate : new String[] {"row_pill.png", "row_frame.png",
                "row_button_wide.png", "row_button_small.png", "row_button_small_off.png"}) {
            assertEquals(SlotGeometry.PITCH - 2 * NavigatePanel.INSET,
                    PanelWalk.image(plate).getHeight(),
                    plate + " is not one slot row inset two, so it does not line up with the pill"
                            + " beside it");
        }
    }

    private static Component surface(final List<NavigatePanel.Entry> entries,
                                     final boolean hasPrev, final boolean hasNext) {
        return PanelWalk.surface(NavigatePanel.title(Component.text("Navigate"), entries, "Stop",
                "2/3", hasPrev, hasNext));
    }
}
