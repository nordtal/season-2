package eu.nordtal.season.hungergames;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.messages.spec.MessageSpecCheck;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Every key of the bundle has a method with a name, and every text names exactly its arguments. */
class HungerGamesMessagesTest {

    @Test
    void theSpecAndTheBundleAgree() {
        assertEquals(List.of(), MessageSpecCheck.problems(HungerGamesMessages.class));
    }
}
