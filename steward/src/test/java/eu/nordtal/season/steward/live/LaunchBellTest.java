package eu.nordtal.season.steward.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import eu.nordtal.season.common.time.ManualScheduler;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Tests that the bell rings once at a launch date ahead, and not for a date gone, changed or passed. */
class LaunchBellTest {

    private static final Instant NOW = Instant.parse("2026-11-05T12:00:00Z");

    private final ManualScheduler scheduler = new ManualScheduler();
    private final AtomicInteger rings = new AtomicInteger();
    private final LaunchBell bell = new LaunchBell(scheduler, Clock.fixed(NOW, ZoneOffset.UTC), rings::incrementAndGet);

    @Test
    void aLaunchAheadRingsOnceWhenItPasses() {
        bell.arm(NOW.plusSeconds(90));
        bell.arm(NOW.plusSeconds(90));

        assertEquals(1, scheduler.pending().size(), "the same date sets one timer");
        assertEquals(Duration.ofSeconds(90), scheduler.pending().getFirst().delay());
        scheduler.runPending();
        assertEquals(1, rings.get());
        assertNull(bell.armedFor());
    }

    @Test
    void aChangedOrClosedDateCancelsTheTimer() {
        bell.arm(NOW.plusSeconds(90));
        bell.arm(NOW.plusSeconds(300));

        assertEquals(1, scheduler.pending().size());
        assertEquals(NOW.plusSeconds(300), bell.armedFor());

        bell.arm(null);
        scheduler.runPending();
        assertEquals(0, rings.get(), "a closed season rings nothing");
    }

    @Test
    void aLaunchGoneSetsNoTimer() {
        bell.arm(NOW);
        bell.arm(NOW.minusSeconds(1));

        assertEquals(0, scheduler.pending().size());
    }
}
