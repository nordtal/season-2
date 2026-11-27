package eu.nordtal.season.steward.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.messagerendering.text.DialogueRoom;
import eu.nordtal.season.messagerendering.text.Letters;
import java.util.List;
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
        assertEquals(DialogueRoom.fit(text, DialogueRoom.SPEAK_LINES), alone.fitted());
        assertTrue(alone.fitted().getLast().endsWith(Letters.ELLIPSIS));
        assertFalse(alone.fits());
        assertTrue(alone.width() <= DialogueRoom.BODY);

        final NpcTextMeasure.Measurement asking =
                NpcTextMeasure.measure(new NpcTextMeasure.Request(text, NpcTextMeasure.Kind.BODY, 2));
        assertEquals(DialogueRoom.QUESTION_LINES, asking.lineLimit());
        assertEquals(DialogueRoom.fit(text, DialogueRoom.QUESTION_LINES), asking.fitted());
        assertTrue(asking.fitted().getLast().endsWith(Letters.ELLIPSIS));
        assertFalse(asking.fits());

        final NpcTextMeasure.Measurement shortText =
                NpcTextMeasure.measure(new NpcTextMeasure.Request("Short greeting.", NpcTextMeasure.Kind.BODY, 2));
        assertEquals(shortText.lines(), shortText.fitted());
        assertTrue(shortText.fits());
    }

    @Test
    void aNameOrAnswerIsOneLineMeasuredWithTheLetterWidths() {
        final NpcTextMeasure.Measurement name =
                NpcTextMeasure.measure(new NpcTextMeasure.Request("Ida", NpcTextMeasure.Kind.NAME, 0));
        assertEquals(Letters.boldVisibleWidth("Ida"), name.width(), "the tab sets the name in bold");
        assertEquals(List.of("Ida"), name.fitted());
        assertTrue(name.fits());
        final String longName = "Master ".repeat(10);
        final NpcTextMeasure.Measurement cutName =
                NpcTextMeasure.measure(new NpcTextMeasure.Request(longName, NpcTextMeasure.Kind.NAME, 0));
        assertFalse(cutName.fits());
        assertEquals(List.of(Letters.boldCut(longName, DialogueRoom.NAME)), cutName.fitted());
        assertTrue(cutName.fitted().getFirst().endsWith(Letters.ELLIPSIS));

        final String longAnswer = SENTENCE.repeat(2);
        final NpcTextMeasure.Measurement answer =
                NpcTextMeasure.measure(new NpcTextMeasure.Request(longAnswer, NpcTextMeasure.Kind.ANSWER, 0));
        assertEquals(DialogueRoom.LABEL, answer.room());
        assertEquals(List.of(Letters.boldCut(longAnswer, DialogueRoom.LABEL)), answer.fitted());
        assertTrue(answer.fitted().getFirst().endsWith(Letters.ELLIPSIS));
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
