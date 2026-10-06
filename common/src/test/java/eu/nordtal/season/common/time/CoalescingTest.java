package eu.nordtal.season.common.time;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CoalescingTest {

    private final AtomicInteger runs = new AtomicInteger();
    private final ManualScheduler scheduler = new ManualScheduler();
    private final Coalescing coalescing = new Coalescing(scheduler, Duration.ofSeconds(2), () -> {
        runs.incrementAndGet();
    });

    @Test
    void aBurstOfRequestsIsOneRunAfterTheSettleTime() {
        for (int request = 0; request < 5; request++) {
            coalescing.request();
        }
        assertEquals(1, scheduler.pending().size(), "five requests share the one run that is waiting");
        assertEquals(Duration.ofSeconds(2), scheduler.pending().getFirst().delay());
        assertEquals(0, runs.get(), "the run waits, so the requests behind the first can join it");

        scheduler.runPending();

        assertEquals(1, runs.get());
    }

    @Test
    void aRequestAfterTheRunStartedAsksForAnother() {
        coalescing.request();
        scheduler.runPending();

        coalescing.request();
        assertEquals(1, scheduler.pending().size(), "what changed after the first run began is not in it");
        scheduler.runPending();

        assertEquals(2, runs.get());
    }

    @Test
    void aRunThatThrowsDoesNotSilenceTheRequestsAfterIt() {
        final Coalescing failing = new Coalescing(scheduler, Duration.ofSeconds(2), () -> {
            throw new IllegalStateException("the database is away");
        });
        failing.request();
        try {
            scheduler.runPending();
        } catch (final IllegalStateException expected) {
            // the scheduler's own failure handling is not under test
        }

        failing.request();

        assertEquals(1, scheduler.pending().size());
    }
}
