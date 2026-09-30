package eu.nordtal.s2.common.time;

import java.time.Duration;
import java.util.Objects;

/**
 * How long to pause between tries: {@code first}, then twice the last pause, never more than {@code max}.
 *
 * @param first the first pause, positive
 * @param max   the longest pause, at least {@code first}
 */
public record Backoff(Duration first, Duration max) {

    public Backoff {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(max, "max");
        if (first.isNegative() || first.isZero() || max.compareTo(first) < 0) {
            throw new IllegalArgumentException("a backoff needs 0 < first <= max, got " + first + " and " + max);
        }
    }

    /** Returns a backoff that pauses {@code every} time the same. */
    public static Backoff fixed(final Duration every) {
        return new Backoff(every, every);
    }

    /** Returns the pause after one of {@code last}. */
    public Duration after(final Duration last) {
        final Duration doubled = last.multipliedBy(2);
        return doubled.compareTo(max) > 0 ? max : doubled;
    }
}
