package eu.nordtal.season.messagerendering.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class DialogueRoomTest {

    private static final String LONG = "Die Mine im Norden ist seit dem letzten Winter verschüttet, und niemand traut"
            + " sich mehr hinein. Wer sie wieder öffnet, bekommt von mir einen ganzen Sack Eisen und einen Platz am"
            + " Feuer. Aber sei vorsichtig: In den Gängen hausen Dinge, die das Licht nicht mögen. Nimm Fackeln,"
            + " Spitzhacken und genug Proviant mit, denn der Weg hinab ist lang und kalt und niemand kommt dir nach.";

    @Test
    void wrapKeepsShortTextOnOneLineAndStripsOuterSpaces() {
        assertEquals(List.of("Hallo du da"), DialogueRoom.wrap("  Hallo du da  "));
    }

    @Test
    void fitKeepsTextWithinItsLineLimitAndCutsOverflowWithAnEllipsis() {
        final List<String> wrapped = DialogueRoom.wrap(LONG);
        assertTrue(wrapped.size() > DialogueRoom.SPEAK_LINES, wrapped.toString());

        for (final int limit : List.of(1, DialogueRoom.QUESTION_LINES, DialogueRoom.SPEAK_LINES)) {
            final List<String> fitted = DialogueRoom.fit(LONG, limit);
            assertEquals(limit, fitted.size());
            fitted.forEach(line -> assertTrue(Letters.visibleWidth(line) <= DialogueRoom.BODY, line));
            assertTrue(fitted.getLast().endsWith(Letters.ELLIPSIS), fitted.getLast());
        }
    }

    @Test
    void fitLeavesTextThatAlreadyFitsUntouched() {
        assertEquals(List.of("Hallo du da"), DialogueRoom.fit("  Hallo du da  ", DialogueRoom.SPEAK_LINES));
    }

    @Test
    void fitRefusesLimitsOutsideOneToSpeakLines() {
        assertThrows(IllegalArgumentException.class, () -> DialogueRoom.fit("Hallo", 0));
        assertThrows(IllegalArgumentException.class, () -> DialogueRoom.fit("Hallo", DialogueRoom.SPEAK_LINES + 1));
    }
}
