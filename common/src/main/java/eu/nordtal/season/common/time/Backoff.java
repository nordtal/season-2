package eu.nordtal.season.common.time;

import java.time.Duration;
import java.util.Objects;

/** The pauses between tries: {@code first}, then twice the last pause, up to {@code max}. */
public record Backoff(Duration first, Duration max) {

    public Backoff {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(max, "max");
        if (first.isNegative() || first.isZero() || max.compareTo(first) < 0) {
            throw new IllegalArgumentException("a backoff needs 0 < first <= max, got " + first + " and " + max);
        }
    }

    public static Backoff fixed(final Duration every) {
        return new Backoff(every, every);
    }

    public Duration after(final Duration last) {
        final Duration doubled = last.multipliedBy(2);
        return doubled.compareTo(max) > 0 ? max : doubled;
    }
}
