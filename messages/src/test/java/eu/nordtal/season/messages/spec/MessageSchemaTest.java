package eu.nordtal.season.messages.spec;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.messages.MessageRef;
import org.junit.jupiter.api.Test;

/** What a spec may declare as a value. */
class MessageSchemaTest {

    @MessageSpec("untyped")
    interface Untyped {

        MessageRef price(@Arg("price") Object price);
    }

    @Test
    void aValueOfNoKindIsRefusedWhenTheSchemaIsWritten() {
        final IllegalStateException refused =
                assertThrows(IllegalStateException.class, () -> MessageSchema.entries(Untyped.class));
        assertTrue(refused.getMessage().contains("no kind a message can show"), refused.getMessage());
    }
}
