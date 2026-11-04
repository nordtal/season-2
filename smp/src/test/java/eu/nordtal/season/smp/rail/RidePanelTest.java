package eu.nordtal.season.smp.rail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.packrendering.Glyphs;
import eu.nordtal.season.packrendering.menu.MenuFont;
import eu.nordtal.season.packrendering.menu.MenuTitle;
import eu.nordtal.season.packrendering.menu.SlotGeometry;
import eu.nordtal.season.smp.menu.PanelWalk;
import eu.nordtal.season.smp.menu.PanelWalk.Run;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

class RidePanelTest {

    private static final List<RidePanel.Row> THREE = List.of(
            new RidePanel.Row("Harbour", "40 m", true),
            new RidePanel.Row("Spawn", "120 m", false),
            new RidePanel.Row("Bäckerei am Fluss mit langem Namen", "1240 m", false));

    private static Component surface(final List<RidePanel.Row> rows, final int stationRows, final boolean paged) {
        return PanelWalk.surface(
                RidePanel.title(Component.text("Where to?"), rows, "None", stationRows, "2/3", paged, true, true));
    }

    @Test
    void aWindowHasOneRowPerStationAndARowForThePageButtonsOnlyWhenPaged() {
        assertEquals(1, RidePanel.rows(0, 5));
        assertEquals(3, RidePanel.rows(3, 5));
        assertEquals(5, RidePanel.rows(5, 5));
        assertEquals(6, RidePanel.rows(6, 5));
        assertEquals(3, RidePanel.rows(7, 2));
        assertEquals(4, RidePanel.pages(7, 2));
        assertEquals(1, RidePanel.pages(0, 5));
    }

    @Test
    void theReadableTitleStillLandsWhereItWould() {
        for (final boolean paged : new boolean[] {false, true}) {
            final List<Run> runs = PanelWalk.runs(surface(THREE, paged ? 5 : 3, paged));
            assertEquals(MenuTitle.ANCHOR_X, runs.getLast().end(), "the drawing has to add up to nothing");
        }
    }

    @Test
    void everyStationRowIsWhereItSaysItIs() {
        final List<Run> runs = PanelWalk.runs(surface(THREE, 3, false));
        for (int row = 0; row < THREE.size(); row++) {
            final String font = Glyphs.FONT_GUI_ROWS.get(row);
            assertEquals(
                    RidePanel.PILL_X,
                    PanelWalk.find(runs, font, Glyphs.GUI_ROW_PILL_LONG).x());
            final List<Run> text = PanelWalk.textRuns(runs, font);
            assertEquals(2, text.size(), "row " + row + " draws a name and a distance");
            assertEquals(MenuFont.fold(THREE.get(row).distance()), text.get(1).content());
            assertEquals(
                    RidePanel.PILL_X + RidePanel.PILL_WIDTH - 3, text.get(1).end());
            assertTrue(text.get(0).end() <= text.get(1).x(), "row " + row + "'s name runs into its distance");

            final Run star = PanelWalk.find(runs, font, Glyphs.GUI_ROW_BUTTON_SMALL);
            final int cell = SlotGeometry.x(RidePanel.STAR_COLUMN);
            assertTrue(star.x() >= cell && star.end() - 1 < cell + SlotGeometry.PITCH, "the star stays in its cell");
        }
    }

    @Test
    void aStationRowReachesUpToTheStarWithoutAGap() {
        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7), RidePanel.stationSlots(0));
        assertEquals(SlotGeometry.x(RidePanel.STAR_COLUMN) - RidePanel.INSET, RidePanel.PILL_X + RidePanel.PILL_WIDTH);
    }

    @Test
    void everyRowDrawsOneStar() {
        final List<Run> runs = PanelWalk.runs(surface(THREE, 3, false));
        for (int row = 0; row < THREE.size(); row++) {
            final String font = Glyphs.FONT_GUI_ROWS.get(row);
            assertEquals(
                    1,
                    runs.stream()
                            .filter(run ->
                                    run.font().equals(font) && run.content().equals(Glyphs.GUI_ROW_ICON_FAVOURITE))
                            .count());
        }
    }

    @Test
    void thePageButtonsSitOnTheirSlotsBelowTheStations() {
        final List<Run> runs = PanelWalk.runs(surface(THREE, 5, true));
        final String font = Glyphs.FONT_GUI_ROWS.get(5);
        for (final int slot : List.of(RidePanel.previousSlot(5), RidePanel.nextSlot(5))) {
            final int cell = SlotGeometry.x(SlotGeometry.column(slot));
            assertTrue(
                    runs.stream()
                            .filter(run -> run.font().equals(font))
                            .anyMatch(run -> run.content().equals(Glyphs.GUI_ROW_BUTTON_SMALL)
                                    && run.x() == cell + RidePanel.INSET),
                    "no page button on the cell of slot " + slot);
            assertEquals(5, SlotGeometry.row(slot));
        }
    }

    @Test
    void anEmptyListDrawsItsLineOnADarkPlate() {
        final List<Run> runs = PanelWalk.runs(surface(List.of(), 1, false));
        final String font = Glyphs.FONT_GUI_ROWS.get(0);
        assertEquals(
                RidePanel.PILL_X,
                PanelWalk.find(runs, font, Glyphs.GUI_ROW_PILL_DARK).x());
        assertEquals(
                List.of(MenuFont.fold("None")),
                PanelWalk.textRuns(runs, font).stream().map(Run::content).toList());
    }

    @Test
    void nothingIsDrawnOutOfAFontThatLacksIt() {
        final List<String> missing = new ArrayList<>();
        for (final Run run : PanelWalk.runs(surface(THREE, 5, true))) {
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
    void theFavouriteButtonSaysWhatAClickDoes() {
        assertEquals(
                "Add to favourites",
                PanelWalk.lines(PanelWalk.document(RideMenu.DOCUMENT, Locale.ENGLISH, new RideMenu.Window(1, 1))
                                .item("favourite", new RideMenu.Stop("Harbour", 40, false)))
                        .getFirst());
        assertEquals(
                "Remove from favourites",
                PanelWalk.lines(PanelWalk.document(RideMenu.DOCUMENT, Locale.ENGLISH, new RideMenu.Window(1, 1))
                                .item("favourite", new RideMenu.Stop("Harbour", 40, true)))
                        .getFirst());
    }

    @Test
    void theStationMenuNamesTheRulesAndTheRefusals() {
        final StationMenu.Window window = new StationMenu.Window(true, false, "Spawn", "Mine", StationNames.MAX_LENGTH);
        final var english = PanelWalk.document(StationMenu.DOCUMENT, Locale.ENGLISH, window);
        assertEquals(
                "At most 32 characters. No other station on these rails may have the same name.",
                english.label("dialog-rules"));
        assertEquals(
                "Another station on these rails is already called Mine. Pick another name.",
                english.label("name-taken"));
        assertEquals(
                "Ein anderer Bahnhof an diesen Schienen heißt schon Mine. Wähl einen anderen Namen.",
                PanelWalk.document(StationMenu.DOCUMENT, Locale.GERMAN, window).label("name-taken"));
    }
}
