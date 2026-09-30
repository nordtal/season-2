package eu.nordtal.s2.steward.worker.bunq;

import eu.nordtal.s2.database.notify.Channel;
import eu.nordtal.s2.database.notify.SignalHub;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import lombok.extern.slf4j.Slf4j;

/**
 * Drives {@link Payments} with a bunq poll, and a pass on every payment signal of the process's hub.
 *
 * A {@code tryLock} keeps a signal mid-pass from starting a second pass.
 */
@Slf4j
public final class PaymentLoop implements AutoCloseable {

    /** One pass, as a {@link Runnable} so a test can count passes without a bank or a database. */
    private final Runnable work;

    private final ScheduledExecutorService timer;
    private final ReentrantLock running = new ReentrantLock();

    PaymentLoop(final Runnable work, final ScheduledExecutorService timer) {
        this.work = work;
        this.timer = timer;
    }

    /**
     * Starts the bunq poll.
     *
     * @param payments the work
     * @param poll how often to ask bunq, which sends no signal of its own
     * @return the running loop, to be put on the process's hub with {@link #listen} and closed with the container
     */
    public static PaymentLoop start(final Payments payments, final Duration poll) {
        final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(runnable -> {
            final Thread thread = new Thread(runnable, "steward-worker-bunq-poll");
            thread.setDaemon(true);
            return thread;
        });

        final PaymentLoop loop = new PaymentLoop(payments::pass, timer);
        // Zero initial delay drains anything left from the previous container.
        final var _ = timer.scheduleWithFixedDelay(loop::pass, 0, poll.toSeconds(), TimeUnit.SECONDS);
        return loop;
    }

    /** Runs a pass on this loop's own thread on every payment signal, so a bunq call never blocks the hub. */
    public void listen(final SignalHub signals) {
        signals.on(Channel.PAYMENT, "the payment seam", () -> timer.execute(this::pass));
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

    @Override
    public void close() {
        timer.shutdownNow();
    }
}
