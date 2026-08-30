package eu.nordtal.jcore.config;

import eu.nordtal.jcore.config.internal.UnknownKeyDetector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The suggestion has to be good enough to act on, and quiet when it would be noise. */
class UnknownKeyDetectorTest {

    private static final List<String> KNOWN =
            List.of("check-interval-seconds", "confirmation-channel-id", "balance");

    @Test
    @DisplayName("a one-character slip suggests the intended key")
    void suggestsOnTypo() {
        assertAll(
                () -> assertEquals("check-interval-seconds",
                        UnknownKeyDetector.suggest("check-intervall-seconds", KNOWN)),
                () -> assertEquals("check-interval-seconds",
                        UnknownKeyDetector.suggest("check-interval-second", KNOWN)),
                () -> assertEquals("confirmation-channel-id",
                        UnknownKeyDetector.suggest("confirmation-chanel-id", KNOWN))
        );
    }

    @Test
    @DisplayName("a key that resembles nothing gets no suggestion, so the known list is shown instead")
    void noSuggestionForUnrelatedKey() {
        assertAll(
                () -> assertNull(UnknownKeyDetector.suggest("completely-different-thing", KNOWN)),
                () -> assertNull(UnknownKeyDetector.suggest("x", KNOWN))
        );
    }

    @Test
    @DisplayName("case is ignored when guessing")
    void caseInsensitive() {
        assertEquals("balance", UnknownKeyDetector.suggest("Balance", KNOWN));
    }

    @Test
    @DisplayName("a correct tree produces no findings")
    void noFindingsForCorrectTree() {
        final Map<String, Object> data = new LinkedHashMap<>();
        data.put("check-interval-seconds", 10);
        data.put("confirmation-channel-id", "1");
        data.put("balance", Map.of("channel-id", "2", "format", "%s"));

        assertTrue(UnknownKeyDetector.detect(TestSpecs.Payments.class, data).isEmpty());
    }

    @Test
    @DisplayName("every unknown key is reported, not just the first")
    void reportsAllUnknownKeys() {
        final Map<String, Object> data = new LinkedHashMap<>();
        data.put("check-interval-second", 10);
        final Map<String, Object> balance = new LinkedHashMap<>();
        balance.put("chanel-id", "2");
        balance.put("formt", "%s");
        data.put("balance", balance);

        final List<String> paths = UnknownKeyDetector.detect(TestSpecs.Payments.class, data)
                .stream().map(UnknownKeyDetector.UnknownKey::path).toList();

        assertEquals(List.of("check-interval-second", "balance.chanel-id", "balance.formt"), paths);
    }

    @Test
    @DisplayName("the message names the known settings when there is nothing to suggest")
    void messageListsKnownSettings() {
        final Map<String, Object> data = new LinkedHashMap<>();
        data.put("totally-unrelated-setting", 1);

        final String message = UnknownKeyDetector.detect(TestSpecs.Payments.class, data).get(0).describe();

        assertAll(
                () -> assertTrue(message.contains("check-interval-seconds"), message),
                () -> assertTrue(message.contains("balance"), message)
        );
    }
}
