package eu.nordtal.s2.steward.worker.bunq;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.common.notify.Channels;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * One pass at a time, and the channel both halves of the seam agree on.
 *
 * The timer thread and the listener thread both call {@link PaymentLoop#pass()}, and a pass makes HTTP calls to a
 * bank. A second pass alongside the first would corrupt nothing - every write in the seam is guarded by the schema -
 * but it would ask bunq the same questions twice, and doing that to somebody else's rate limit because a
 * notification happened to land mid-pass is not a trade worth making. {@code tryLock} rather than {@code lock} is
 * the other half of it: a caller who finds a pass running has nothing to add by waiting for it, because that pass is
 * about to read the same rows.
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

            // Once the first pass is done, the next wake-up runs for real - asserts the lock was actually released.
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
