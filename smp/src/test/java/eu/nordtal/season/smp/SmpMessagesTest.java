package eu.nordtal.season.smp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.messages.spec.MessageSpecCheck;
import java.util.List;
import org.junit.jupiter.api.Test;

class SmpMessagesTest {

    @Test
    void theSpecAndTheBundleAgree() {
        assertEquals(List.of(), MessageSpecCheck.problems(SmpMessages.class));
    }
}
