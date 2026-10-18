package eu.nordtal.season.database.inbox;

import java.time.Duration;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * When a request may run and how long it waits for its consumer, on the database's clock.
 *
 * @param delay the wait from now until the consumer may claim it. Never negative
 * @param patience the wait after that until an unclaimed request expires, null for never
 */
public record Schedule(Duration delay, @Nullable Duration patience) {

    /** Due at once, never expires. */
    public static final Schedule NOW = new Schedule(Duration.ZERO, null);

    public Schedule {
        Objects.requireNonNull(delay, "delay");
        // A negative delay is due at once, and a negative patience expires at once.
        delay = delay.isNegative() ? Duration.ZERO : delay;
        patience = patience == null || !patience.isNegative() ? patience : Duration.ZERO;
    }

    /** Returns a schedule that is due at once and expires unclaimed after {@code patience}. */
    public static Schedule within(final Duration patience) {
        return new Schedule(Duration.ZERO, Objects.requireNonNull(patience, "patience"));
    }

    /** Returns a schedule that is due after {@code delay} and never expires. */
    public static Schedule after(final Duration delay) {
        return new Schedule(delay, null);
    }
}
