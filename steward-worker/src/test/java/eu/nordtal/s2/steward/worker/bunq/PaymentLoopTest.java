package eu.nordtal.s2.steward.worker.bunq;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.database.notify.Channels;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
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

        final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
        try {
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
                    timer);

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
        } finally {
            timer.shutdownNow();
        }
    }

    @Test
    void theLoopListensOnTheChannelTheSeamPublishesOn() {
        // A listener on a channel nobody publishes on looks like a working one: it logs, connects, and never fires.
        assertEquals(Channels.PAYMENT, PaymentLoop.channel());
        assertEquals("nordtal_payment", PaymentLoop.channel());
    }
}
