package eu.nordtal.s2.proxy.online;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The arithmetic behind {@code online_count}, in memory - see {@link OnlineCounts} for why it never
 * touches a real {@code ProxyServer}.
 */
class OnlineCountsTest {

    @Test
    @DisplayName("the network total always appears, even with no backend registered at all")
    void theTotalAlwaysAppears() {
        final Map<String, Integer> counts = OnlineCounts.of(0, Map.of());

        assertEquals(Map.of(OnlineCounts.PROXY, 0), counts);
    }

    @Test
    @DisplayName("every registered backend's count is carried through untouched")
    void everyBackendIsCarriedThrough() {
        final Map<String, Integer> counts =
                OnlineCounts.of(5, Map.of("smp", 2, "limbo", 3));

        assertEquals(2, counts.get("smp"));
        assertEquals(3, counts.get("limbo"));
        assertEquals(5, counts.get(OnlineCounts.PROXY));
        assertEquals(3, counts.size(), "the three backends plus proxy, nothing invented");
    }

    @Test
    @DisplayName("a backend not currently registered is absent, never guessed at zero")
    void anUnregisteredBackendIsAbsent() {
        // hunger-games is not in playersByServer at all - the proxy has no such server right now.
        final Map<String, Integer> counts = OnlineCounts.of(4, Map.of("smp", 4));

        assertFalse(counts.containsKey("hunger-games"),
                "a subject nothing measured this tick must not be written as 0");
    }

    @Test
    void aNullMapIsRejectedRatherThanNpeingLater() {
        assertThrows(NullPointerException.class, () -> OnlineCounts.of(0, null));
    }
}
