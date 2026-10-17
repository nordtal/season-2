package eu.nordtal.season.messages.spec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.spec.layout.LayoutTexts;
import eu.nordtal.season.messages.value.Example;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A value's example and the keys a message value takes have to be ones it can take. */
class MessageSpecCheckTest {

    enum Phase {
        RUNNING,
        STOPPED,
        PAUSED
    }

    @MessageSpec("choices")
    @Shown(Display.CHAT)
    interface Picked {

        @Name("State")
        MessageRef state(@Arg("state") Phase state);

        @Name("Shown")
        MessageRef shown(@Arg("shown") @Example("false") boolean shown);
    }

    @MessageSpec("choices")
    @Shown(Display.CHAT)
    interface Unpicked {

        @Name("State")
        MessageRef state(@Arg("state") @Example("true") Phase state);
    }

    @Test
    void anEnumWithoutAnExampleIsPreviewedAsItsFirstConstant() {
        assertEquals(
                "running",
                MessageSchema.entries(Picked.class).getFirst().args().getFirst().example());
        assertEquals(List.of(), MessageSpecCheck.problems(Picked.class));
    }

    @MessageSpec("choices")
    @Shown(Display.CHAT)
    interface Unnamed {

        @Name("State")
        MessageRef state(@Arg("state") @Example("paused") Phase state);

        @Name("Shown")
        MessageRef shown(@Arg("shown") @Example("true") boolean shown);
    }

    @Test
    void anEnumsExampleThatNamesNoneOfItsCasesIsRefused() {
        assertEquals(
                List.of(
                        "state (en): the example paused of state names none of its cases",
                        "state (de): the example paused of state names none of its cases"),
                MessageSpecCheck.problems(Unnamed.class));
    }

    @Test
    void anExampleThatIsNoneOfTheChoicesValuesIsRefused() {
        final IllegalStateException refused =
                assertThrows(IllegalStateException.class, () -> MessageSpecCheck.problems(Unpicked.class));
        assertEquals(
                true,
                refused.getMessage()
                        .endsWith("the example true of state is none of its values running, stopped, paused"));
    }

    @MessageSpec("taking")
    @Shown(Display.DISCORD_EMBED)
    interface Taking {

        @Name("Steps")
        MessageRef steps(@Arg("button") @Takes({"nowhere", "values/missing.text", "not a key"}) MessageRef button);
    }

    @Test
    void aKeyAMessageValueTakesIsOneItsBundleDeclares() {
        assertEquals(
                List.of(
                        "steps: @Takes names nowhere, which this bundle does not declare",
                        "steps: @Takes names not a key, which is neither key nor bundle/key"),
                MessageSpecCheck.problems(Taking.class));
    }

    @Test
    void aMessageValueWithoutAnExampleIsPreviewedAsTheFirstKeyItTakes() {
        final MessageSchema.Arg button = MessageSchema.entries(LayoutTexts.class).stream()
                .filter(entry -> entry.key().equals("steps"))
                .findFirst()
                .orElseThrow()
                .args()
                .getFirst();

        assertEquals("buy", button.example());
        assertEquals(List.of("buy"), button.takes());
        assertEquals(List.of(), MessageSpecCheck.problems(LayoutTexts.class));
    }

    @MessageSpec("taking")
    @Shown(Display.DISCORD_EMBED)
    interface TakingAText {

        @Name("Steps")
        MessageRef steps(@Arg("button") @Takes("buy") String button);
    }

    @MessageSpec("taking")
    @Shown(Display.DISCORD_EMBED)
    interface ExampleNotTaken {

        @Name("Steps")
        MessageRef steps(@Arg("button") @Takes("buy") @Example("sell") MessageRef button);
    }

    @Test
    void onlyAMessageValueTakesKeysAndItsExampleIsOneOfThem() {
        assertTrue(assertThrows(IllegalStateException.class, () -> MessageSchema.entries(TakingAText.class))
                .getMessage()
                .endsWith("button is a text, and only a message takes keys"));
        assertTrue(assertThrows(IllegalStateException.class, () -> MessageSchema.entries(ExampleNotTaken.class))
                .getMessage()
                .endsWith("the example sell of button is none of its values buy"));
    }
}
