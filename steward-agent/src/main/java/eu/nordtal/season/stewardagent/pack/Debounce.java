package eu.nordtal.season.stewardagent.pack;

import eu.nordtal.season.common.time.Scheduler;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executor;
import org.jspecify.annotations.Nullable;

/** Runs {@code work} once after a quiet time with no new request, so a burst of changes makes one build. */
final class Debounce {

    private final Scheduler scheduler;
    private final Duration quiet;
    private final Executor lane;
    private final Runnable work;

    private Scheduler.@Nullable Task waiting;

    /**
     * @param quiet the time without a request before {@code work} runs
     * @param lane where {@code work} runs, so two runs never overlap
     */
    Debounce(final Scheduler scheduler, final Duration quiet, final Executor lane, final Runnable work) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.quiet = Objects.requireNonNull(quiet, "quiet");
        this.lane = Objects.requireNonNull(lane, "lane");
        this.work = Objects.requireNonNull(work, "work");
    }

    /** Asks for a run, which waits for {@code quiet} after the last request. */
    synchronized void request() {
        if (waiting != null) {
            waiting.cancel();
        }
        waiting = scheduler.after(quiet, () -> lane.execute(work));
    }
}
