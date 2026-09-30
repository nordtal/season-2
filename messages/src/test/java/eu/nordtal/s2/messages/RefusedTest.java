package eu.nordtal.s2.messages;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RefusedTest {

    enum Door implements RefusalReason {
        LOCKED
    }

    @Test
    void aRefusalCarriesItsTypedReasonAndAMessageRenderedWhereItIsShown() {
        final Messages messages = Messages.load("messages/test", Locale.ENGLISH);
        final MessageRef message = new MessageRef("greeting", Map.of("name", "Ada"));

        final Refused refused = new Refused(Door.LOCKED, message);

        assertSame(Door.LOCKED, refused.reason());
        assertEquals(new Refusal(Door.LOCKED, message), refused.refusal());
        assertEquals(
                "Hello Ada!", messages.format(Locale.ENGLISH, refused.refusal().message()));
        assertEquals("LOCKED: greeting", refused.getMessage());
    }
}
