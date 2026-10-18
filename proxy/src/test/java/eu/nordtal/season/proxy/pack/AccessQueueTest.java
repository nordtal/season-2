package eu.nordtal.season.proxy.pack;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.proxy.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** One player onto {@code smp} per interval, in the order they became ready. */
class AccessQueueTest {

    private static final UUID FIRST = new UUID(0, 1);
    private static final UUID SECOND = new UUID(0, 2);
    private static final UUID THIRD = new UUID(0, 3);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-18T19:00:00Z"));
    private final AtomicReference<Duration> interval = new AtomicReference<>(Duration.ofSeconds(3));
    private final AccessQueue queue = new AccessQueue("smp", interval::get, clock);

    @Test
    void oneSlotPerInterval() {
        assertTrue(queue.mayGo(FIRST, "smp"));
        assertFalse(queue.mayGo(SECOND, "smp"), "the slot is spent until the interval has passed");
        assertTrue(queue.isWaiting());

        clock.advance(Duration.ofSeconds(2));
        assertFalse(queue.mayGo(SECOND, "smp"));
        clock.advance(Duration.ofSeconds(1));
        assertTrue(queue.mayGo(SECOND, "smp"));
        assertFalse(queue.isWaiting());
    }

    @Test
    void theOrderIsKept() {
        assertTrue(queue.mayGo(FIRST, "smp"));
        assertFalse(queue.mayGo(SECOND, "smp"));
        assertFalse(queue.mayGo(THIRD, "smp"));
        clock.advance(Duration.ofSeconds(3));

        // The sweep reaches the third player first; the second became ready earlier.
        assertFalse(queue.mayGo(THIRD, "smp"));
        assertTrue(queue.mayGo(SECOND, "smp"));
    }

    @Test
    void aLeaverDropsOutWithoutCostingASlot() {
        assertTrue(queue.mayGo(FIRST, "smp"));
        assertFalse(queue.mayGo(SECOND, "smp"));
        assertFalse(queue.mayGo(THIRD, "smp"));
        queue.leave(SECOND);
        clock.advance(Duration.ofSeconds(3));

        assertTrue(queue.mayGo(THIRD, "smp"));
    }

    @Test
    void aFailedConnectionKeepsTheSlot() {
        assertTrue(queue.mayGo(FIRST, "smp"));
        assertFalse(queue.mayGo(SECOND, "smp"));
        queue.refund(FIRST);

        assertTrue(queue.mayGo(SECOND, "smp"), "the slot was never used");
    }

    @Test
    void aChangedIntervalAppliesAtOnce() {
        assertTrue(queue.mayGo(FIRST, "smp"));
        interval.set(Duration.ofSeconds(1));
        clock.advance(Duration.ofSeconds(1));

        assertTrue(queue.mayGo(SECOND, "smp"));
    }

    @Test
    void otherServersAreNotQueued() {
        assertTrue(queue.mayGo(FIRST, "smp"));
        assertTrue(queue.mayGo(SECOND, "hunger-games"));
        assertFalse(queue.isWaiting());
    }
}
