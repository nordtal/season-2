package eu.nordtal.season.proxy.season;

import eu.nordtal.season.common.season.SeasonOpening;
import eu.nordtal.season.common.season.SeasonState;
import eu.nordtal.season.common.time.Scheduler;
import eu.nordtal.season.database.season.SeasonDirectory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Holds the launch date, maintenance and the active mode of the season.
 * The signal hub triggers {@link #refresh()}, and a timer re-reads at the launch date.
 */
public final class SeasonWatch {

    /** Receives each change of the state, and the opening when the launch date passes. */
    @FunctionalInterface
    public interface ChangeListener {

        /** @param previous the state before the change, {@code null} on the first read */
        void seasonChanged(@Nullable SeasonState previous, SeasonState current);
    }

    /** The last read state and its opening at that read. */
    private record Known(SeasonState state, SeasonOpening opening) {}

    private final SeasonDirectory seasons;
    private final Scheduler scheduler;
    private final Clock clock;
    private final Logger logger;
    private final ChangeListener listener;

    /** The last read value, {@code null} before the first read. */
    private final AtomicReference<@Nullable Known> known = new AtomicReference<>();

    /** The launch date that {@link #armed} re-reads at, {@code null} without a timer. */
    private @Nullable Instant armedFor;

    private Scheduler.@Nullable Task armed;

    public SeasonWatch(
            final SeasonDirectory seasons,
            final Scheduler scheduler,
            final Clock clock,
            final Logger logger,
            final ChangeListener listener) {
        this.seasons = Objects.requireNonNull(seasons, "seasons");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    /**
     * Reads the state from the database and tells the listener about a change or an opening, one read at a time.
     *
     * @return {@code false} when the database does not answer, then {@link #state()} keeps its value
     */
    public synchronized boolean refresh() {
        final SeasonState current;
        try {
            current = seasons.state();
        } catch (final RuntimeException exception) {
            logger.warn("season state unreadable, last state kept: season=\"{}\"", state(), exception);
            return false;
        }
        final Instant now = clock.instant();
        final Known next = new Known(current, current.opening(now));
        final Known before = known.getAndSet(next);
        arm(current.launch(), now);
        if (next.equals(before)) {
            return true;
        }
        final SeasonState previous = before == null ? null : before.state();
        if (before == null) {
            logger.info("season state read: season=\"{}\" opening={}", current, next.opening());
        } else {
            logger.warn(
                    "season state changed: from=\"{}\" from_opening={} to=\"{}\" to_opening={}",
                    previous,
                    before.opening(),
                    current,
                    next.opening());
        }
        notifyListener(previous, current);
        return true;
    }

    /** Returns the last read state, or {@link SeasonState#UNREAD} before the first read. */
    public SeasonState state() {
        final Known current = known.get();
        return current == null ? SeasonState.UNREAD : current.state();
    }

    /** Returns whether the state was read once. */
    public boolean everRead() {
        return known.get() != null;
    }

    /** Returns the launch date the timer waits for, {@code null} without a timer. */
    synchronized @Nullable Instant armedFor() {
        return armedFor;
    }

    /** Sets a timer to re-read at a launch date ahead, and cancels a timer for another date. */
    private synchronized void arm(final @Nullable Instant launch, final Instant now) {
        if (launch != null && launch.equals(armedFor)) {
            return;
        }
        if (armed != null) {
            armed.cancel();
        }
        armed = null;
        armedFor = null;
        if (launch == null || !now.isBefore(launch)) {
            return;
        }
        armedFor = launch;
        armed = scheduler.after(Duration.between(now, launch), () -> fired(launch));
    }

    /** Clears the timer of the launch date and re-reads. A timer that fires early is set again by the read. */
    private void fired(final Instant launch) {
        synchronized (this) {
            if (!launch.equals(armedFor)) {
                return;
            }
            armed = null;
            armedFor = null;
        }
        final boolean _ = refresh();
    }

    private void notifyListener(final @Nullable SeasonState previous, final SeasonState current) {
        try {
            listener.seasonChanged(previous, current);
        } catch (final RuntimeException exception) {
            logger.error("season change listener failed: from=\"{}\" to=\"{}\"", previous, current, exception);
        }
    }
}
