package eu.nordtal.s2.common.metric;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * How much time one row of {@code metric_sample} stands for.
 *
 * The names are the strings in the column - the same arrangement {@code UpdateKind} has with
 * {@code update_request.kind}, and it carries the same standing obligation: a value added here
 * without a migration widening the {@code CHECK} compiles, passes every unit test, and is refused
 * by a real database at the moment somebody writes one.
 */
public enum Resolution {

    /** One measurement, at the instant it was taken. Written every 30 seconds by the collector. */
    RAW,

    /** The mean of one UTC hour of {@link #RAW} samples, timestamped at the hour's start. */
    HOUR;

    /**
     * Returns the start of the UTC hour an instant falls in, the {@code at} of its {@link #HOUR} row.
     *
     * UTC, matching the migration's {@code floor(epoch / 3600)} alignment check.
     */
    public static Instant hourOf(final Instant at) {
        return at.truncatedTo(ChronoUnit.HOURS);
    }
}
