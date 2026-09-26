package eu.nordtal.s2.smp.grave;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.common.Glyphs;
import eu.nordtal.s2.common.menu.MenuFont;
import eu.nordtal.s2.common.menu.MenuTitle;
import eu.nordtal.s2.common.menu.SlotGeometry;
import eu.nordtal.s2.smp.menu.PanelWalk;
import eu.nordtal.s2.smp.menu.PanelWalk.Run;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

/**
 * Walks the grave's composed window and holds it against the five slabs the pack drew.
 *
 * The two that carry rules rather than measurements are {@link #everyFooterCellIsClaimed} - a free footer cell is
 * where a shift-clicked item lands and is then lost on close, silently - and
 * {@link #theSlabIsExactlyTheContentRows}, because a slab one row out is a row of items sitting on bare panel or a
 * row of empty recesses under the footer, and neither fails anywhere.
 */
class GravePanelTest {

    @Test
    void theReadableTitleStillLandsWhereItWould() {
        for (int rows = 1; rows <= GravePanel.MAX_CONTENT_ROWS; rows++) {
            final List<Run> runs = PanelWalk.runs(surface(rows));
            assertEquals(
                    MenuTitle.ANCHOR_X,
                    runs.get(runs.size() - 1).end(),
                    "a grave of " + rows + " row(s) does not add up to nothing");
        }
    }

    @Test
    void theSlabIsExactlyTheContentRows() {
        for (int rows = 1; rows <= GravePanel.MAX_CONTENT_ROWS; rows++) {
            final Run slab =
                    PanelWalk.find(PanelWalk.runs(surface(rows)), Glyphs.FONT_GUI, Glyphs.GUI_GRAVE_SLAB.get(rows - 1));
            final BufferedImage art = PanelWalk.image("grave_slab_" + rows + ".png");

            assertEquals(
                    SlotGeometry.x(0),
                    slab.x(),
                    "the slab starts at the first slot CELL's own corner, not inset - it IS the" + " cells");
            assertEquals(SlotGeometry.x(8) + SlotGeometry.PITCH, slab.x() + art.getWidth());
            assertEquals(
                    rows * SlotGeometry.PITCH,
                    art.getHeight(),
                    "grave_slab_" + rows + " is not " + rows + " slot rows tall. One row too short"
                            + " and the last row of items sits on bare panel; one too tall and it"
                            + " runs under the footer, where a row of empty recesses appears behind"
                            + " the head and the button");
            assertEquals(art.getWidth() + 1, slab.advance());
        }
    }

    @Test
    void theSlabIsRecessesAndNotATray() {
        // The deliberate opposite of the hand-in tray: a grave's cells say these are distinct stacks, any takeable.
        final BufferedImage slab = PanelWalk.image("grave_slab_2.png");
        final Set<Integer> columnColours = new LinkedHashSet<>();
        for (int x = 0; x < slab.getWidth(); x++) {
            columnColours.add(slab.getRGB(x, 4));
        }
        assertTrue(
                columnColours.size() >= 2,
                "row 4 of the slab is one flat colour all the way across, so there is no cell edge"
                        + " in it and the grave is drawn as a tray");

        // The lit edge of each cell, at x = 17 within every 18-pixel step, is what makes it a grid.
        final int edge = slab.getRGB(17, 4);
        for (int column = 0; column < SlotGeometry.COLUMNS; column++) {
            assertEquals(
                    edge,
                    slab.getRGB(column * SlotGeometry.PITCH + 17, 4),
                    "cell " + column + " has no lit right edge, so the recesses do not tile");
        }
        assertTrue(edge != slab.getRGB(4, 4), "a cell's edge and its field are the same colour");
    }

    @Test
    void everyFooterCellIsClaimed() {
        for (int rows = 1; rows <= GravePanel.MAX_CONTENT_ROWS; rows++) {
            final Set<Integer> claimed = new LinkedHashSet<>();
            claimed.add(GravePanel.headSlot(rows));
            claimed.addAll(GravePanel.experienceSlots(rows));
            claimed.addAll(GravePanel.takeAllSlots(rows));

            assertEquals(
                    SlotGeometry.COLUMNS,
                    claimed.size(),
                    "the footer of a " + rows + "-row grave leaves a cell free or claims one twice."
                            + " A shift-click from the player's own inventory goes into the first"
                            + " FREE slot of the window, so a free footer cell is a slot outside"
                            + " everything the settle writes back - the item is simply gone on"
                            + " close and nothing fails");
            for (final int slot : claimed) {
                assertEquals(GravePanel.footerRow(rows), SlotGeometry.row(slot));
                assertTrue(!GravePanel.isContent(slot, rows), "slot " + slot + " is both footer and content");
            }
        }
    }

    @Test
    void theContentIsWhatIsAboveTheFooter() {
        for (int rows = 1; rows <= GravePanel.MAX_CONTENT_ROWS; rows++) {
            assertEquals(rows * SlotGeometry.COLUMNS, GravePanel.contentSlots(rows));
            for (int slot = 0; slot < GravePanel.rows(rows) * SlotGeometry.COLUMNS; slot++) {
                assertEquals(
                        SlotGeometry.row(slot) < rows,
                        GravePanel.isContent(slot, rows),
                        "slot " + slot + " of a " + rows + "-row grave");
            }
        }
    }

    @Test
    void theRowCountFollowsWhatWasCarried() {
        // An experience-only grave is real: a death dropping nothing but levels. One row, not an empty window.
        assertEquals(1, GravePanel.contentRows(0));
        assertEquals(1, GravePanel.contentRows(1));
        assertEquals(1, GravePanel.contentRows(9));
        assertEquals(2, GravePanel.contentRows(10));
        // Thirty-six inventory slots, four of armour and one off-hand is the most anybody carries.
        assertEquals(5, GravePanel.contentRows(41));
        assertEquals(
                GravePanel.MAX_CONTENT_ROWS,
                GravePanel.contentRows(41),
                "forty-one stacks is what a player can carry, and it has to fit in the five rows"
                        + " that are left once the footer has taken the sixth");
        assertEquals(GravePanel.MAX_CONTENT_ROWS, GravePanel.contentRows(999));

        for (int rows = 1; rows <= GravePanel.MAX_CONTENT_ROWS; rows++) {
            assertTrue(
                    GravePanel.rows(rows) <= MenuTitle.MAX_ROWS,
                    "a grave window has to be a chest the pack has a panel for");
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> GravePanel.title(Component.empty(), GravePanel.MAX_CONTENT_ROWS + 1, "", "X"));
        assertThrows(IllegalArgumentException.class, () -> GravePanel.title(Component.empty(), 0, "", "X"));
    }

    @Test
    void theButtonIsItsSlots() {
        for (int rows = 1; rows <= GravePanel.MAX_CONTENT_ROWS; rows++) {
            final String font = Glyphs.FONT_GUI_ROWS.get(GravePanel.footerRow(rows));
            final Run plate = PanelWalk.find(PanelWalk.runs(surface(rows)), font, Glyphs.GUI_ROW_BUTTON_TAKE);
            final List<Integer> slots = GravePanel.takeAllSlots(rows);
            assertEquals(SlotGeometry.x(SlotGeometry.column(slots.get(0))) + GravePanel.INSET, plate.x());
            assertTrue(
                    plate.end() - 1 <= SlotGeometry.x(SlotGeometry.column(slots.get(3))) + SlotGeometry.PITCH,
                    "the plate runs past the last cell that carries its click");
        }
    }

    @Test
    void theExperienceStaysOutOfTheButtonsWay() {
        final Component title = GravePanel.title(
                Component.text("Grave"), 3, "+999999999 XP and a great deal more text than that", "Take all");
        for (final Run run : PanelWalk.textRuns(
                PanelWalk.runs(PanelWalk.surface(title)), Glyphs.FONT_GUI_ROWS.get(GravePanel.footerRow(3)))) {
            if (run.x() < GravePanel.TAKE_X) {
                assertTrue(run.end() <= GravePanel.TAKE_X, "'" + run.content() + "' runs under the take-all button");
            }
        }
    }

    @Test
    void anEmptyExperienceLineDrawsNothing() {
        final List<Run> runs =
                PanelWalk.runs(PanelWalk.surface(GravePanel.title(Component.text("Grave"), 2, "", "Take all")));
        final List<Run> text = PanelWalk.textRuns(runs, Glyphs.FONT_GUI_ROWS.get(2));
        assertEquals(
                1,
                text.size(),
                "a grave that paid out no experience should draw the button's label and nothing"
                        + " else - a bare '+0 XP' is a promise of nothing, written out: " + text);
        assertEquals(MenuFont.fold("Take all"), text.get(0).content());
    }

    @Test
    void nothingIsDrawnOutOfAFontThatLacksIt() {
        final List<String> missing = new ArrayList<>();
        for (int rows = 1; rows <= GravePanel.MAX_CONTENT_ROWS; rows++) {
            for (final Run run : PanelWalk.runs(surface(rows))) {
                final Set<Integer> declared = PanelWalk.declared(run.font());
                run.whole().codePoints().forEach(codePoint -> {
                    if (!declared.contains(codePoint)) {
                        missing.add("U+%X in %s".formatted(codePoint, run.font()));
                    }
                });
            }
        }
        assertEquals(List.of(), missing);
    }

    @Test
    void theDrawnKeysAreTagless() {
        final List<String> tagged = new ArrayList<>();
        for (final String language : new String[] {"en", "de"}) {
            final Properties bundle = PanelWalk.bundle(language);
            for (final String key : new String[] {"smp.grave.experience-line", "smp.grave.take-all-button"}) {
                final String value = bundle.getProperty(key);
                assertTrue(value != null, language + " does not declare " + key);
                if (value.indexOf('<') >= 0 || value.indexOf('>') >= 0) {
                    tagged.add(language + " " + key + " = " + value);
                }
            }
        }
        assertEquals(
                List.of(),
                tagged,
                "these two are drawn in the pack's five-pixel sheet by MenuFont, which prints them"
                        + " character for character - a tag would reach the player as literal text");
    }

    private static Component surface(final int contentRows) {
        return PanelWalk.surface(GravePanel.title(Component.text("Grave"), contentRows, "+120 XP", "Take all"));
    }
}
