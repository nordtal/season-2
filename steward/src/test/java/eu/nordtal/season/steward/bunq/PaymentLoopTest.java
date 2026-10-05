package eu.nordtal.season.steward.bunq;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.time.ManualScheduler;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * One pass at a time, and the channel both halves of the seam agree on.
 *
 * A second pass would ask bunq the same questions twice, and a caller that finds one running has nothing to add.
 */
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

        // The notification arriving while the bank is still answering the poll.
        loop.pass();
        assertEquals(
                1,
                passes.get(),
                "the second caller started a pass of its own; bunq is being asked the same"
                        + " questions twice for every notification that lands mid-pass");

        release.countDown();
        first.join(5_000);

        // Once the first pass is done the next wake-up runs, so the lock was released.
        loop.pass();
        assertEquals(2, passes.get(), "the lock was not released");
    }
}
