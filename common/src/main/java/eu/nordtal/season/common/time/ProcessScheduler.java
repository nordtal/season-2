package eu.nordtal.season.common.time;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** The scheduler of a JVM process: one timer thread and a virtual thread per run. A throw goes to {@code failures}. */
public final class ProcessScheduler implements Scheduler, AutoCloseable {

    private final ScheduledExecutorService timer;
    private final ExecutorService runs;
    private final Consumer<RuntimeException> failures;

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
