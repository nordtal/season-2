package eu.nordtal.season.messages.spec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.value.Example;
import org.junit.jupiter.api.Test;

class MessageSchemaTest {

    @MessageSpec("untyped")
    @Shown(Display.CHAT)
    interface Untyped {

        MessageRef price(@Arg("price") Object price);
    }

    @Test
    void aValueOfNoKindIsRefusedWhenTheSchemaIsWritten() {
        final IllegalStateException refused =
                assertThrows(IllegalStateException.class, () -> MessageSchema.entries(Untyped.class));
        assertTrue(refused.getMessage().contains("no kind a message can show"), refused.getMessage());
    }

    @MessageSpec("unexampled")
    @Shown(Display.CHAT)
    interface Unexampled {

        MessageRef missing(@Arg("layout") String layout);
    }

    @MessageSpec("exampled")
    @Shown(Display.CHAT)
    interface Exampled {

        MessageRef missing(@Example("Classic") @Arg("layout") String layout, @Arg("open") boolean open);
    }

    @Test
    void aValueWithoutAnExampleIsRefusedWhenTheSchemaIsWritten() {
        final IllegalStateException refused =
                assertThrows(IllegalStateException.class, () -> MessageSchema.entries(Unexampled.class));
        assertTrue(refused.getMessage().contains("layout needs an @Example"), refused.getMessage());
    }

    @Test
    void aValueShowsItsExampleAndAChoiceItsFirstValue() {
        final var args = MessageSchema.entries(Exampled.class).getFirst().args();
        assertEquals("Classic", args.get(0).example());
        assertEquals("true", args.get(1).example());
    }
}
