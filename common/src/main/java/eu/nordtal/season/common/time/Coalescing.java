package eu.nordtal.season.common.time;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Joins a burst of requests into one run. The first request waits for {@code settle}.
 * A request after the run started asks for another run.
 */
public final class Coalescing {

    private final Scheduler scheduler;
    private final Duration settle;
    private final Runnable work;
    private final AtomicBoolean waiting = new AtomicBoolean();

    public Coalescing(final Scheduler scheduler, final Duration settle, final Runnable work) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.settle = Objects.requireNonNull(settle, "settle");
        this.work = Objects.requireNonNull(work, "work");
    }

    public void request() {
        if (waiting.compareAndSet(false, true)) {
            final var _ = scheduler.after(settle, () -> {
                waiting.set(false);
                work.run();
            });
        }
    }
}
