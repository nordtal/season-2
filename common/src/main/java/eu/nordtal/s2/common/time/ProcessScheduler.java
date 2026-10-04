package eu.nordtal.s2.common.time;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The scheduler of a plain JVM process: one timer thread that only hands work on, and a virtual thread per run.
 *
 * Work may block on I/O as long as it likes; a throw goes to {@code failures}, and a repeating task carries on.
 */
public final class ProcessScheduler implements Scheduler, AutoCloseable {

    private final ScheduledExecutorService timer;
    private final ExecutorService runs;
    private final Consumer<RuntimeException> failures;

    /**
     * Starts the threads of process {@code name}, which name every thread they start.
     *
     * @param failures told of every throw of a run, which nothing else sees
     */
    public ProcessScheduler(final String name, final Consumer<RuntimeException> failures) {
        this.timer = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name(name + "-timer").daemon().factory());
        this.runs = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name(name + "-", 0).factory());
        this.failures = failures;
    }

    @Override
    public void execute(final Runnable work) {
        runs.execute(() -> runGuarded(work));
    }

    @Override
    public Task after(final Duration delay, final Runnable work) {
        final ScheduledFuture<?> due = timer.schedule(() -> execute(work), delay.toNanos(), TimeUnit.NANOSECONDS);
        return () -> due.cancel(false);
    }

    /** Stops the timer and interrupts every run still going. */
    @Override
    public void close() {
        timer.shutdownNow();
        runs.shutdownNow();
    }

    private void runGuarded(final Runnable work) {
        try {
            work.run();
        } catch (final RuntimeException failure) {
            failures.accept(failure);
        }
    }
}
