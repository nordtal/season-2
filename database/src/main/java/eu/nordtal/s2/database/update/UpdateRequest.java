package eu.nordtal.s2.database.update;

import eu.nordtal.s2.database.Actor;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One run: a row of the worker's inbox, what was asked for, by whom, and what happened.
 *
 * @param scheduledFor the instant the worker may claim it
 * @param countdownEnd when the servers go down, {@code null} until the worker's plan has work and counts down
 * @param moving       the services this run stops, empty until the countdown starts
 * @param started      when a worker claimed it, {@code null} while {@link UpdateStatus#PENDING}
 * @param finished     when it reached a terminal state, {@code null} until then
 * @param result       the report, verbatim, {@code null} until finished
 */
public record UpdateRequest(
        long id,
        UpdateKind kind,
        UpdateStatus status,
        Actor actor,
        Instant requested,
        Instant scheduledFor,
        @Nullable Instant countdownEnd,
        List<String> moving,
        @Nullable Instant started,
        @Nullable Instant finished,
        @Nullable String result) {

    public UpdateRequest {
        moving = List.copyOf(moving);
    }

    /** Returns what this row counts towards: the countdown once one has started, else the scheduled instant. */
    public Instant due() {
        return countdownEnd != null ? countdownEnd : scheduledFor;
    }

    /**
     * Returns the whole seconds until {@link #due()}, never negative.
     *
     * @param now the instant to measure from
     */
    public long secondsUntilDue(final Instant now) {
        final long seconds = due().getEpochSecond() - now.getEpochSecond();
        return Math.max(0L, seconds);
    }

    /**
     * Returns the time until {@link #due()} to the millisecond, never negative, so a countdown lands on time.
     *
     * @param now the instant to measure from
     */
    public Duration untilDue(final Instant now) {
        final Duration left = Duration.between(now, due());
        return left.isNegative() ? Duration.ZERO : left;
    }
}
