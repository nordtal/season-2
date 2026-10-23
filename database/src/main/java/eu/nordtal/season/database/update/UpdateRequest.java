package eu.nordtal.season.database.update;

import eu.nordtal.season.common.id.Actor;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One run: a row of the run inbox, with what was asked, by whom and what happened.
 *
 * @param scheduledFor the instant at which steward may claim it
 * @param countdownEnd when the servers go down, null until the plan has work and counts down
 * @param moving the services the run stops, empty until the countdown starts
 * @param started when steward claimed it, null while {@link UpdateStatus#PENDING}
 * @param result the report verbatim, null until finished
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

    /** Returns the path of the page of this run in Steward. */
    public String page() {
        return kind.page(id);
    }

    /** Returns the countdown end if it has started, else the scheduled instant. */
    public Instant due() {
        return countdownEnd != null ? countdownEnd : scheduledFor;
    }

    /** Returns the whole seconds until {@link #due()}, never negative. */
    public long secondsUntilDue(final Instant now) {
        final long seconds = due().getEpochSecond() - now.getEpochSecond();
        return Math.max(0L, seconds);
    }

    /** Returns the time until {@link #due()} to the millisecond, never negative. */
    public Duration untilDue(final Instant now) {
        final Duration left = Duration.between(now, due());
        return left.isNegative() ? Duration.ZERO : left;
    }
}
