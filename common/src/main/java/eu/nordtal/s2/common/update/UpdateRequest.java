package eu.nordtal.s2.common.update;

import java.time.Duration;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * One row of {@code update_request}: what was asked for, by whom, and what happened.
 *
 * @param requestedBy a Discord id, a Minecraft name, or {@code null} for the console
 * @param notBefore   the instant the worker may act
 * @param started     when a worker claimed it, {@code null} while {@link UpdateStatus#PENDING}
 * @param finished    when it reached a terminal state, {@code null} until then
 * @param result      the report, verbatim, {@code null} until finished
 */
public record UpdateRequest(
        long id,
        UpdateKind kind,
        UpdateStatus status,
        UpdateSource source,
        @Nullable String requestedBy,
        Instant requested,
        Instant notBefore,
        @Nullable Instant started,
        @Nullable Instant finished,
        @Nullable String result) {

    /**
     * Returns the whole seconds until this may run, never negative.
     *
     * @param now the instant to measure from
     */
    public long secondsUntilDue(final Instant now) {
        final long seconds = notBefore.getEpochSecond() - now.getEpochSecond();
        return Math.max(0L, seconds);
    }

    /**
     * Returns the time until this may run to the millisecond, never negative, so a per-second countdown lands on time.
     *
     * @param now the instant to measure from
     */
    public Duration untilDue(final Instant now) {
        final Duration left = Duration.between(now, notBefore);
        return left.isNegative() ? Duration.ZERO : left;
    }
}
