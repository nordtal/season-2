package eu.nordtal.season.steward.live;

import eu.nordtal.season.common.time.Scheduler;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Rings the live feed when a launch date ahead passes, so a page sees the season open without a signal. */
public final class LaunchBell {

    private final Scheduler scheduler;
    private final Clock clock;
    private final Runnable ring;

    /** The launch date that {@link #armed} rings at, {@code null} without a timer. */
    private @Nullable Instant armedFor;

    private Scheduler.@Nullable Task armed;

    public LaunchBell(final Scheduler scheduler, final Clock clock, final Runnable ring) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ring = Objects.requireNonNull(ring, "ring");
    }

    /** Sets a timer for a launch date ahead, and cancels a timer for another date or none. */
    public synchronized void arm(final @Nullable Instant launch) {
        if (launch != null && launch.equals(armedFor)) {
            return;
        }
        if (armed != null) {
            armed.cancel();
        }
        armed = null;
        armedFor = null;
        final Instant now = clock.instant();
        if (launch == null || !now.isBefore(launch)) {
            return;
        }
        armedFor = launch;
        armed = scheduler.after(Duration.between(now, launch), () -> fired(launch));
    }

    /** Returns the launch date the timer waits for, {@code null} without a timer. */
    synchronized @Nullable Instant armedFor() {
        return armedFor;
    }

    /** Clears the timer and rings. The pass reads the season again, and a timer that fired early is set again. */
    private void fired(final Instant launch) {
        synchronized (this) {
            if (!launch.equals(armedFor)) {
                return;
            }
            armed = null;
            armedFor = null;
        }
        ring.run();
    }
}
