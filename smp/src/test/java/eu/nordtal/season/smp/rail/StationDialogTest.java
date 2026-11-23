package eu.nordtal.season.smp.rail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.packrendering.ui.Letters;
import java.util.List;
import org.junit.jupiter.api.Test;

class StationDialogTest {

    @Test
    void aHintThatFitsStaysOneRow() {
        assertEquals(List.of("Rechtsklick, dann fahren."), StationDialog.phrases("Rechtsklick, dann fahren.", 186));
    }

    @Test
    void aRowEndsAfterTheCommaWhereTheNextPhraseDoesNotFitBesideIt() {
        final String first = "Rechtsklick mit einer Lore,";
        final String second = "dann fährst du los.";
        final int room = Math.max(Letters.visibleWidth(first), Letters.visibleWidth(second));

        assertEquals(List.of(first, second), StationDialog.phrases(first + " " + second, room));
    }

    @Test
    void aPhraseWiderThanARowWrapsAtItsSpacesAndNoRowOutgrowsTheRoom() {
        final String text = "Setze eine Lore auf die Schiene und klicke sie mit der rechten Maustaste an";
        final List<String> rows = StationDialog.phrases(text, 80);

        assertTrue(rows.size() > 1);
        rows.forEach(row -> assertTrue(Letters.visibleWidth(row) <= 80, row));
        assertEquals(text, String.join(" ", rows));
    }
}
