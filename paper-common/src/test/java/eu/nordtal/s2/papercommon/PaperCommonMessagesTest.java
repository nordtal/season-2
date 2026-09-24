package eu.nordtal.s2.papercommon;

import eu.nordtal.s2.common.message.spec.MessageSpecCheck;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Every key of the bundle has a method with a name, and every text names exactly its arguments. */
class PaperCommonMessagesTest {

    @Test
    void theSpecAndTheBundleAgree() {
        assertEquals(List.of(), MessageSpecCheck.problems(PaperCommonMessages.class));
    }
}
