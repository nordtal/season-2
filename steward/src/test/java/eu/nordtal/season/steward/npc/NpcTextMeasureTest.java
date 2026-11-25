package eu.nordtal.season.steward.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.messagerendering.text.DialogueRoom;
import eu.nordtal.season.messagerendering.text.Letters;
import org.junit.jupiter.api.Test;

/** Tests the length check against the panel's room, measured with the pack's letter widths. */
class NpcTextMeasureTest {

    private static final String SENTENCE = "The wheel turns once a day, and every turn may bring a prize. ";

    @Test
    void aBodyOverThePageLineLimitDoesNotFit() {
        String text = "";
        while (DialogueRoom.wrap(text).size() < DialogueRoom.SPEAK_LINES + 1) {
            text += "turn ";
        }
        final NpcTextMeasure.Measurement alone =
                NpcTextMeasure.measure(new NpcTextMeasure.Request(text, NpcTextMeasure.Kind.BODY, 0));
        assertEquals(DialogueRoom.SPEAK_LINES, alone.lineLimit());
        assertEquals(DialogueRoom.BODY, alone.room());
        assertEquals(
                DialogueRoom.SPEAK_LINES + 1,
                alone.lines().size(),
                alone.lines().toString());
        assertFalse(alone.fits());
        assertTrue(alone.width() <= DialogueRoom.BODY);

        final NpcTextMeasure.Measurement asking =
                NpcTextMeasure.measure(new NpcTextMeasure.Request(text, NpcTextMeasure.Kind.BODY, 2));
        assertEquals(DialogueRoom.QUESTION_LINES, asking.lineLimit());
        assertFalse(asking.fits());

        final NpcTextMeasure.Measurement shortText =
                NpcTextMeasure.measure(new NpcTextMeasure.Request("Short greeting.", NpcTextMeasure.Kind.BODY, 2));
        assertTrue(shortText.fits());
    }

    @Test
    void aNameOrAnswerIsOneLineMeasuredWithTheLetterWidths() {
        final NpcTextMeasure.Measurement name =
                NpcTextMeasure.measure(new NpcTextMeasure.Request("Ida", NpcTextMeasure.Kind.NAME, 0));
        assertEquals(Letters.boldVisibleWidth("Ida"), name.width(), "the tab sets the name in bold");
        assertTrue(name.fits());
        final NpcTextMeasure.Measurement answer =
                NpcTextMeasure.measure(new NpcTextMeasure.Request(SENTENCE.repeat(2), NpcTextMeasure.Kind.ANSWER, 0));
        assertEquals(DialogueRoom.LABEL, answer.room());
        assertFalse(answer.fits());
    }

    @Test
    void aMalformedRequestIsRefused() {
        assertThrows(
                IllegalArgumentException.class,
                () -> NpcTextMeasure.measure(new NpcTextMeasure.Request("Hi", NpcTextMeasure.Kind.BODY, 4)));
        assertThrows(
                IllegalArgumentException.class,
                () -> NpcTextMeasure.measure(new NpcTextMeasure.Request(null, NpcTextMeasure.Kind.BODY, 0)));
    }
}
