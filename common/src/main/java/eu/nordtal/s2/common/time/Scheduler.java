package eu.nordtal.s2.common.time;

import java.time.Duration;
import java.util.concurrent.Executor;

/**
 * How a process runs work off the caller's thread: now, after a delay, or again and again.
 *
 * A process has one, made where it starts and handed to whatever schedules; no feature owns a thread pool or a timer.
 */
public interface Scheduler extends Executor {

    /** Runs {@code work} once, as soon as a thread takes it; a throw is logged and goes no further. */
    @Override
    void execute(Runnable work);

    /** Runs {@code work} once after {@code delay}, unless cancelled first. */
    Task after(Duration delay, Runnable work);

    /**
     * Runs {@code work} after {@code delay} and then every {@code period}, until cancelled.
     *
     * Never two runs at once: one that overran is followed at once by the next, and no missed run is made up.
     */
    Task every(Duration delay, Duration period, Runnable work);

    /** Returns a lane that runs what it is handed one at a time, in the order handed, on this scheduler's threads. */
    default Executor serial() {
        return new SerialLane(this);
    }

    /** Work waiting to run, which can be called off. */
    @FunctionalInterface
    interface Task {

        /** Calls off every run that has not started; one already running finishes. */
        void cancel();
    }
}
