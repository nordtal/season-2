package eu.nordtal.season.steward;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class RequestLimitTest {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-23T12:00:00Z"));

    private void advance(final Duration by) {
        now.set(now.get().plus(by));
    }

    @Test
    void aRefusedKeyLearnsWhenItsOldestAttemptLeavesTheWindow() {
        final RequestLimit<String> limit = new RequestLimit<>(now::get, Duration.ofMinutes(1), 2, 10);
        assertEquals(Optional.empty(), limit.acquireOrWait("a"));
        advance(Duration.ofSeconds(20));
        assertEquals(Optional.empty(), limit.acquireOrWait("a"));
        advance(Duration.ofSeconds(5));

        assertEquals(Optional.of(Duration.ofSeconds(35)), limit.acquireOrWait("a"));
        assertEquals(Optional.empty(), limit.acquireOrWait("b"));
    }

    @Test
    void aFullWindowNamesTheWaitForEveryKey() {
        final RequestLimit<String> limit = new RequestLimit<>(now::get, Duration.ofMinutes(1), 5, 2);
        assertEquals(Optional.empty(), limit.acquireOrWait("a"));
        advance(Duration.ofSeconds(10));
        assertEquals(Optional.empty(), limit.acquireOrWait("b"));
        advance(Duration.ofSeconds(10));

        assertEquals(Optional.of(Duration.ofSeconds(40)), limit.acquireOrWait("c"));
        advance(Duration.ofSeconds(40));
        assertEquals(Optional.empty(), limit.acquireOrWait("c"));
    }
}
