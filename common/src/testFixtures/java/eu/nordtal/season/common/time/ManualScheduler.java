package eu.nordtal.season.common.time;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A scheduler whose time a test moves: work handed to it now runs at once, timed work when the test says.
 *
 * Nothing runs on another thread, so a test sees each run finish before it asserts.
 */
public final class ManualScheduler implements Scheduler {

    /** One piece of timed work waiting, with the delay it was handed over with. */
    public static final class Pending implements Task {

        private final Duration delay;
        private final @Nullable Duration period;
        private final Runnable work;
        private boolean cancelled;

        private Pending(final Duration delay, final @Nullable Duration period, final Runnable work) {
            this.delay = delay;
            this.period = period;
            this.work = work;
        }

        public Duration delay() {
            return delay;
        }

        @Override
        public void cancel() {
            cancelled = true;
        }
    }

    private final List<Pending> pending = new ArrayList<>();

    @Override
    public void execute(final Runnable work) {
        work.run();
    }

    @Override
    public Task after(final Duration delay, final Runnable work) {
        return add(new Pending(delay, null, work));
    }

    @Override
    public Task every(final Duration delay, final Duration period, final Runnable work) {
        return add(new Pending(delay, period, work));
    }

    /** Returns the timed work still waiting, in the order it was handed over. */
    public List<Pending> pending() {
        pending.removeIf(waiting -> waiting.cancelled);
        return List.copyOf(pending);
    }

    /** Runs every piece of timed work waiting now once; a one-off is then done, a repeating one waits again. */
    public void runPending() {
        for (final Pending due : pending()) {
            if (due.period == null) {
                pending.remove(due);
            }
            due.work.run();
        }
    }

    private Pending add(final Pending waiting) {
        pending.add(waiting);
        return waiting;
    }
}
