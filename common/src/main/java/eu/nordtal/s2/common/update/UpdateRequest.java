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
     * How long until this may run, from a caller's clock.
     *
     * Clamped at zero rather than going negative: every caller of this is rendering a countdown,
     * and "-3 seconds" is not a thing to show a player.
     *
     * @param now the instant to measure from
     * @return whole seconds remaining, never negative
     */
    public long secondsUntilDue(final Instant now) {
        final long seconds = notBefore.getEpochSecond() - now.getEpochSecond();
        return Math.max(0L, seconds);
    }

    /**
     * The same, to the millisecond.
     *
     * <b>Why the whole seconds above are not enough</b>
     *
     * The proxy schedules one task per second of the last ten, each on the exact instant its number
     * is true. Truncating to whole seconds first would put every one of them up to 999 ms early or
     * late - so the counter would show 3 while 2.1 seconds were left, and the number nobody may
     * disbelieve would be the one that is wrong.
     *
     * @param now the instant to measure from
     * @return milliseconds remaining, never negative
     */
    public Duration untilDue(final Instant now) {
        final Duration left = Duration.between(now, notBefore);
        return left.isNegative() ? Duration.ZERO : left;
    }
}
