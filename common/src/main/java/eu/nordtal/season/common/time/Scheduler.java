package eu.nordtal.season.common.time;

import java.time.Duration;
import java.util.concurrent.Executor;

/** Runs work off the caller's thread: now, after a delay or repeatedly. */
public interface Scheduler extends Executor {

    @Override
    void execute(Runnable work);

    Task after(Duration delay, Runnable work);

    /** Runs {@code work} after {@code delay} and then every {@code period}, until cancelled. */
    default Task every(final Duration delay, final Duration period, final Runnable work) {
        final Repeating repeating = new Repeating(this, delay, period, work);
        repeating.arm();
        return repeating;
    }

    /** Returns an executor that runs its tasks one at a time, in order. */
    default Executor serial() {
        return new SerialLane(this);
    }

    @FunctionalInterface
    interface Task {

        /** Calls off every run that has not started; one already running finishes. */
        void cancel();
    }
}
