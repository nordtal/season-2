package eu.nordtal.season.steward.bunq;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.time.ManualScheduler;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Tests that only one pass runs at a time. */
class PaymentLoopTest {

    @Test
    void aWakeUpThatArrivesMidPassIsDroppedNotQueuedBehindIt() throws Exception {
        final AtomicInteger passes = new AtomicInteger();
        final CountDownLatch inside = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        final PaymentLoop loop = new PaymentLoop(
                () -> {
                    passes.incrementAndGet();
                    inside.countDown();
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (final InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                },
                new ManualScheduler());

        final Thread first = new Thread(loop::pass, "first-pass");
        first.start();
        assertTrue(inside.await(5, TimeUnit.SECONDS), "the first pass never started");

        // A signal arrives during the pass of the poll.
        loop.pass();
        assertEquals(
                1,
                passes.get(),
                "the second caller started a pass of its own; bunq is being asked the same"
                        + " questions twice for every notification that lands mid-pass");

        release.countDown();
        first.join(5_000);

        // After the first pass ends, the next wake-up runs.
        loop.pass();
        assertEquals(2, passes.get(), "the lock was not released");
    }
}
