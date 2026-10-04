package eu.nordtal.s2.common.time;

import java.time.Duration;
import java.util.concurrent.RejectedExecutionException;
import org.jspecify.annotations.Nullable;

/** {@link Scheduler#every}: each run arms the next through {@link Scheduler#after} once it has ended. */
final class Repeating implements Scheduler.Task {

    private final Scheduler scheduler;
    private final long period;
    private final Runnable work;

    /** When the next run is due, by {@link System#nanoTime()}; only the one run going touches it. */
    private long due;

    private volatile boolean cancelled;
    private volatile Scheduler.@Nullable Task next;

    Repeating(final Scheduler scheduler, final Duration delay, final Duration period, final Runnable work) {
        this.scheduler = scheduler;
        this.period = period.toNanos();
        this.work = work;
        this.due = System.nanoTime() + delay.toNanos();
    }

    void arm() {
        if (cancelled) {
            return;
        }
        try {
            next = scheduler.after(Duration.ofNanos(Math.max(0, due - System.nanoTime())), this::runOnce);
        } catch (final RejectedExecutionException stopping) {
            // The scheduler is shutting down; nothing more is due.
            cancelled = true;
        }
    }

    private void runOnce() {
        if (cancelled) {
            return;
        }
        try {
            work.run();
        } finally {
            // A throw is the scheduler's to report; the next run is armed either way.
            due = Math.max(due + period, System.nanoTime());
            arm();
        }
    }

    @Override
    public void cancel() {
        cancelled = true;
        final Scheduler.Task armed = next;
        if (armed != null) {
            armed.cancel();
        }
    }
}
