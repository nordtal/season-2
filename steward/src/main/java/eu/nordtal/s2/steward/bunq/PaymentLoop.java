package eu.nordtal.s2.steward.bunq;

import eu.nordtal.s2.common.time.Scheduler;
import eu.nordtal.s2.database.notify.Channel;
import eu.nordtal.s2.database.notify.SignalHub;
import java.time.Duration;
import java.util.concurrent.locks.ReentrantLock;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

/**
 * Drives {@link Payments} with a bunq poll, and a pass on every payment signal of the process's hub.
 *
 * A {@code tryLock} keeps a signal mid-pass from starting a second pass.
 */
@Slf4j
public final class PaymentLoop implements AutoCloseable {

    /** One pass, as a {@link Runnable} so a test can count passes without a bank or a database. */
    private final Runnable work;

    private final Scheduler scheduler;
    private final ReentrantLock running = new ReentrantLock();
    private Scheduler.@Nullable Task polling;

    PaymentLoop(final Runnable work, final Scheduler scheduler) {
        this.work = work;
        this.scheduler = scheduler;
    }

    /**
     * Starts the bunq poll.
     *
     * @param payments the work
     * @param poll how often to ask bunq, which sends no signal of its own
     * @return the running loop, to be put on the process's hub with {@link #listen} and closed with the container
     */
    public static PaymentLoop start(final Payments payments, final Duration poll, final Scheduler scheduler) {
        final PaymentLoop loop = new PaymentLoop(payments::pass, scheduler);
        // Zero initial delay drains anything left from the previous container.
        loop.polling = scheduler.every(Duration.ZERO, poll, loop::pass);
        return loop;
    }

    /** Runs a pass off the hub's thread on every payment signal, so a bunq call never blocks the hub. */
    public void listen(final SignalHub signals) {
        signals.on(Channel.PAYMENT, "the payment seam", () -> scheduler.execute(this::pass));
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
        if (polling != null) {
            polling.cancel();
        }
    }
}
