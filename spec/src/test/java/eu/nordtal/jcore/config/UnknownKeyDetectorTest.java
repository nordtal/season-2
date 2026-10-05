package eu.nordtal.jcore.config;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.jcore.config.internal.UnknownKeyDetector;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The suggestion has to be good enough to act on, and quiet when it would be noise. */
class UnknownKeyDetectorTest {

    private static final List<String> KNOWN = List.of("check-interval-seconds", "confirmation-channel-id", "balance");

    @Test
    void suggestsOnTypo() {
        assertAll(
                () -> assertEquals(
                        "check-interval-seconds", UnknownKeyDetector.suggest("check-intervall-seconds", KNOWN)),
                () -> assertEquals(
                        "check-interval-seconds", UnknownKeyDetector.suggest("check-interval-second", KNOWN)),
                () -> assertEquals(
                        "confirmation-channel-id", UnknownKeyDetector.suggest("confirmation-chanel-id", KNOWN)));
    }

    @Test
    void noSuggestionForUnrelatedKey() {
        assertAll(
                () -> assertNull(UnknownKeyDetector.suggest("completely-different-thing", KNOWN)),
                () -> assertNull(UnknownKeyDetector.suggest("x", KNOWN)));
    }

    @Test
    void caseInsensitive() {
        assertEquals("balance", UnknownKeyDetector.suggest("Balance", KNOWN));
    }

    @Test
    void noFindingsForCorrectTree() {
        final Map<String, Object> data = new LinkedHashMap<>();
        data.put("check-interval-seconds", 10);
        data.put("confirmation-channel-id", "1");
        data.put("balance", Map.of("channel-id", "2", "format", "%s"));

        assertTrue(UnknownKeyDetector.detect(TestSpecs.Payments.class, data).isEmpty());
    }

    @Test
    void reportsAllUnknownKeys() {
        final Map<String, Object> data = new LinkedHashMap<>();
        data.put("check-interval-second", 10);
        final Map<String, Object> balance = new LinkedHashMap<>();
        balance.put("chanel-id", "2");
        balance.put("formt", "%s");
        data.put("balance", balance);

        final List<String> paths = UnknownKeyDetector.detect(TestSpecs.Payments.class, data).stream()
                .map(UnknownKeyDetector.UnknownKey::path)
                .toList();

        assertEquals(List.of("check-interval-second", "balance.chanel-id", "balance.formt"), paths);
    }

    @Test
    void probableTypoTracksTheSuggestion() {
        final Map<String, Object> data = new LinkedHashMap<>();
        data.put("check-interval-second", 10);
        data.put("legacy-contribution-tiers", 3);

        final List<UnknownKeyDetector.UnknownKey> found = UnknownKeyDetector.detect(TestSpecs.Payments.class, data);

        // A typo is refused and kept; a retired key has nothing to suggest and is deleted.
        assertAll(
                () -> assertTrue(found.get(0).probableTypo(), found.get(0).describe()),
                () -> assertFalse(found.get(1).probableTypo(), found.get(1).describe()));
    }

    @Test
    void messageListsKnownSettings() {
        final Map<String, Object> data = new LinkedHashMap<>();
        data.put("totally-unrelated-setting", 1);

        final String message =
                UnknownKeyDetector.detect(TestSpecs.Payments.class, data).get(0).describe();

        assertAll(
                () -> assertTrue(message.contains("check-interval-seconds"), message),
                () -> assertTrue(message.contains("balance"), message));
    }
}
