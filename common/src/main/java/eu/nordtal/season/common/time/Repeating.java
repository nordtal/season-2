package eu.nordtal.season.common.time;

import java.time.Duration;
import java.util.concurrent.RejectedExecutionException;
import org.jspecify.annotations.Nullable;

/** {@link Scheduler#every}: arms the next run when a run has ended. */
final class Repeating implements Scheduler.Task {

    private final Scheduler scheduler;
    private final long period;
    private final Runnable work;

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
