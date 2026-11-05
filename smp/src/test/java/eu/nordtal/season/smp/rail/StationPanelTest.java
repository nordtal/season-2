package eu.nordtal.season.smp.rail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.messagerendering.GameDocument;
import eu.nordtal.season.packrendering.Glyphs;
import eu.nordtal.season.packrendering.menu.MenuFont;
import eu.nordtal.season.packrendering.menu.SlotGeometry;
import eu.nordtal.season.smp.menu.PanelWalk;
import eu.nordtal.season.smp.menu.PanelWalk.Run;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

class StationPanelTest {

    private static final String ROW = Glyphs.FONT_GUI_ROWS.get(StationPanel.BUTTON_ROW);

    @Test
    void bothButtonsAreGoldPlatesInOneRowOverTheirOwnSlots() {
        final List<Run> runs = runs("Rename", "Hide");

        final Run name = PanelWalk.find(runs, ROW, Glyphs.GUI_ROW_BUTTON_TAKE);
        final Run visibility = PanelWalk.find(runs, ROW, Glyphs.GUI_ROW_BUTTON_CONFIRM);

        assertEquals(SlotGeometry.x(column(StationPanel.NAME_SLOTS.get(0))) + StationPanel.INSET, name.x());
        assertEquals(SlotGeometry.x(column(StationPanel.VISIBILITY_SLOTS.get(0))) + StationPanel.INSET, visibility.x());
        assertTrue(name.end() < visibility.x(), "the plates overlap");
        assertEquals(
                SlotGeometry.x(column(StationPanel.VISIBILITY_SLOTS.getLast()))
                        + SlotGeometry.PITCH
                        - StationPanel.INSET
                        + 1,
                visibility.end(),
                "the visibility plate does not end on its last slot");
        assertEquals(2, StationPanel.rows(true));
    }

    @Test
    void everyLabelFitsItsPlateUnshortenedInEnglishAndGerman() {
        for (final Locale locale : List.of(Locale.ENGLISH, Locale.GERMAN)) {
            final GameDocument document = PanelWalk.document(
                    StationMenu.DOCUMENT, locale, new StationMenu.Window(true, false, "Hafen", "", 32));
            for (final String[] labels : new String[][] {
                {document.label("name-button"), document.label("hide-button")},
                {document.label("rename-button"), document.label("show-button")}
            }) {
                final List<String> drawn = PanelWalk.textRuns(runs(labels[0], labels[1]), ROW).stream()
                        .map(Run::content)
                        .toList();
                assertEquals(List.of(MenuFont.fold(labels[0]), MenuFont.fold(labels[1])), drawn, locale.toString());
            }
        }
    }

    private static List<Run> runs(final String name, final String visibility) {
        return PanelWalk.runs(PanelWalk.surface(
                StationPanel.title(Component.text("Station"), "Hafen", new StationPanel.Buttons(name, visibility))));
    }

    private static int column(final int slot) {
        return slot % SlotGeometry.COLUMNS;
    }
}
