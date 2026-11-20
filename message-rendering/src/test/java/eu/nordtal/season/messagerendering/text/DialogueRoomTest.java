package eu.nordtal.season.messagerendering.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class DialogueRoomTest {

    private static final String LONG = "Die Mine im Norden ist seit dem letzten Winter verschüttet, und niemand traut"
            + " sich mehr hinein. Wer sie wieder öffnet, bekommt von mir einen ganzen Sack Eisen und einen Platz am"
            + " Feuer. Aber sei vorsichtig: In den Gängen hausen Dinge, die das Licht nicht mögen.";

    @Test
    void aTextThatFitsItsLastPageIsOnePage() {
        assertEquals(List.of(List.of("Hallo du da")), DialogueRoom.pages("Hallo du da", 4));
    }

    @Test
    void aLongTextBreaksIntoPagesThatFitTheirRoomAndLoseNothing() {
        for (final int lastHolds : List.of(1, 3, 4)) {
            final List<List<String>> pages = DialogueRoom.pages(LONG, lastHolds);

            assertTrue(pages.size() > 1, pages.toString());
            for (int page = 0; page < pages.size(); page++) {
                final List<String> lines = pages.get(page);
                final boolean lastPage = page == pages.size() - 1;
                assertTrue(lines.size() <= (lastPage ? lastHolds : DialogueRoom.SPEAK_LINES), lines.toString());
                lines.forEach(line -> assertTrue(Letters.visibleWidth(line) <= DialogueRoom.BODY, line));
                if (!lastPage) {
                    assertTrue(Letters.visibleWidth(lines.getLast()) <= DialogueRoom.BODY - DialogueRoom.COUNT);
                }
            }
            assertEquals(
                    LONG, String.join(" ", pages.stream().flatMap(List::stream).toList()));
        }
    }

    @Test
    void spacesAroundATextAddNoLineAndNoPage() {
        final String padded = "  " + LONG + " ".repeat(200);

        assertEquals(DialogueRoom.pages(LONG, 3), DialogueRoom.pages(padded, 3));
        assertEquals(List.of(List.of("Hallo")), DialogueRoom.pages("Hallo" + " ".repeat(200), 1));
    }

    @Test
    void aLastPageHoldsOneToFourLines() {
        assertThrows(IllegalArgumentException.class, () -> DialogueRoom.pages("Hallo", 0));
        assertThrows(IllegalArgumentException.class, () -> DialogueRoom.pages("Hallo", DialogueRoom.SPEAK_LINES + 1));
    }
}
