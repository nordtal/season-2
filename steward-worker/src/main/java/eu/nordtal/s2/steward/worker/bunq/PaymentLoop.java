package eu.nordtal.s2.steward.worker.bunq;

import eu.nordtal.s2.common.notify.Channels;
import eu.nordtal.s2.common.notify.NotificationListener;
import eu.nordtal.s2.common.notify.Notifications;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

/**
 * Drives {@link Payments} with a poll, and a {@code LISTEN nordtal_payment} that makes it feel immediate.
 *
 * The poll is the guarantee; a {@code tryLock} keeps a notification mid-pass from starting a second pass.
 */
@Slf4j
public final class PaymentLoop implements AutoCloseable {

    /** One pass, as a {@link Runnable} so a test can count passes without a bank or a database. */
    private final Runnable work;

    private final ScheduledExecutorService timer;
    private final ReentrantLock running = new ReentrantLock();

    /** Set once after construction, since the listener and this loop refer to each other. */
    private volatile @Nullable NotificationListener listener;

    PaymentLoop(final Runnable work, final ScheduledExecutorService timer) {
        this.work = work;
        this.timer = timer;
    }

    /**
     * Starts both halves.
     *
     * @param payments the work
     * @param connector opens a dedicated {@code LISTEN} connection, never the pool's
     * @param poll how often to ask bunq regardless of any notification
     * @return the running loop, to be closed with the container
     */
    public static PaymentLoop start(
            final Payments payments, final Notifications.Connector connector, final Duration poll) {
        final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(runnable -> {
            final Thread thread = new Thread(runnable, "steward-worker-bunq-poll");
            thread.setDaemon(true);
            return thread;
        });

        final PaymentLoop loop = new PaymentLoop(payments::pass, timer);
        loop.listener = new NotificationListener(
                connector,
                "steward-worker-bunq-listener",
                List.of(new NotificationListener.Refresh("the payment seam", loop::pass)),
                log,
                poll);
        loop.listener.start();

        // Zero initial delay drains anything left from the previous container.
        final var _ = timer.scheduleWithFixedDelay(loop::pass, 0, poll.toSeconds(), TimeUnit.SECONDS);
        return loop;
    }

    /** Runs one pass, unless one is already running. */
    void pass() {
        if (!running.tryLock()) {
            log.debug("A payment pass is already running; this wake-up adds nothing");
            return;
        }
        try {
            work.run();
        } finally {
            running.unlock();
        }
    }

    /** The channel both halves of the seam wake on. */
    public static String channel() {
        return Channels.PAYMENT;
    }

    @Override
    public void close() {
        timer.shutdownNow();
        final NotificationListener open = listener;
        if (open != null) {
            open.close();
        }
    }
}
