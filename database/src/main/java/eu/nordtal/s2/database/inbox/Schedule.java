package eu.nordtal.s2.database.inbox;

import java.time.Duration;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * When a request may run and how long it waits for its consumer, both counted on the database's clock.
 *
 * @param delay    how long from now until the consumer may claim it; never negative
 * @param patience how long after that an unclaimed request expires, {@code null} for never
 */
public record Schedule(Duration delay, @Nullable Duration patience) {

    /** Due at once, and waiting however long it takes. */
    public static final Schedule NOW = new Schedule(Duration.ZERO, null);

    public Schedule {
        Objects.requireNonNull(delay, "delay");
        // Clamped: a negative delay is due at once, and a negative patience expires at once, which is visible.
        delay = delay.isNegative() ? Duration.ZERO : delay;
        patience = patience == null || !patience.isNegative() ? patience : Duration.ZERO;
    }

    /** Returns a request due at once that expires unclaimed after {@code patience}. */
    public static Schedule within(final Duration patience) {
        return new Schedule(Duration.ZERO, Objects.requireNonNull(patience, "patience"));
    }

    /** Returns a request due after {@code delay}, waiting however long it takes from then. */
    public static Schedule after(final Duration delay) {
        return new Schedule(delay, null);
    }
}
