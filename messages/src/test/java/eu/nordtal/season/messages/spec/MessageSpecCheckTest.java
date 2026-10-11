package eu.nordtal.season.messages.spec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.value.Example;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A choice's example is previewed as the case it picks, so it has to be a value the choice can take. */
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
}
