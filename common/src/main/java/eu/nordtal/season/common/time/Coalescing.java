package eu.nordtal.season.common.time;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Turns a burst of requests into one run: the first waits for {@code settle} and the ones behind it join it.
 *
 * A request after the run started asks for another, since what changed after that run began is not in it.
 */
public final class Coalescing {

    private final Scheduler scheduler;
    private final Duration settle;
    private final Runnable work;
    private final AtomicBoolean waiting = new AtomicBoolean();

    /**
     * Creates the coalescer.
     *
     * @param settle how long the first request of a burst waits for the ones behind it
     * @param work what runs once per burst, on the scheduler's thread
     */
    public Coalescing(final Scheduler scheduler, final Duration settle, final Runnable work) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.settle = Objects.requireNonNull(settle, "settle");
        this.work = Objects.requireNonNull(work, "work");
    }

    /** Asks for a run; it joins the one that is waiting, if any. */
    public void request() {
        if (waiting.compareAndSet(false, true)) {
            final var _ = scheduler.after(settle, () -> {
                waiting.set(false);
                work.run();
            });
        }
    }
}
