package eu.nordtal.s2.hungergames;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.s2.common.message.spec.MessageSpecCheck;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Every key of the bundle has a method with a name, and every text names exactly its arguments. */
class HungerGamesMessagesTest {

    @Test
    void theSpecAndTheBundleAgree() {
        assertEquals(List.of(), MessageSpecCheck.problems(HungerGamesMessages.class));
    }
}
