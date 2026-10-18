package eu.nordtal.season.steward.bunq;

import eu.nordtal.season.common.time.Scheduler;
import eu.nordtal.season.database.notify.Channel;
import eu.nordtal.season.database.notify.SignalHub;
import java.time.Duration;
import java.util.concurrent.locks.ReentrantLock;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

/** Runs {@link Payments} on a bunq poll and on every payment signal. A {@code tryLock} blocks a second pass. */
@Slf4j
public final class PaymentLoop implements AutoCloseable {

    private final Runnable work;

    private final Scheduler scheduler;
    private final ReentrantLock running = new ReentrantLock();
    private Scheduler.@Nullable Task polling;

    PaymentLoop(final Runnable work, final Scheduler scheduler) {
        this.work = work;
        this.scheduler = scheduler;
    }

    public static PaymentLoop start(final Payments payments, final Duration poll, final Scheduler scheduler) {
        final PaymentLoop loop = new PaymentLoop(payments::pass, scheduler);
        // The first pass handles work left from the previous container.
        loop.polling = scheduler.every(Duration.ZERO, poll, loop::pass);
        return loop;
    }

    public void listen(final SignalHub signals) {
        signals.on(Channel.PAYMENT, "the payment seam", () -> scheduler.execute(this::pass));
    }

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
