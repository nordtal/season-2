package eu.nordtal.season.database.metric;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * How much time one row of {@code metric_sample} stands for.
 *
 * The names are the column's strings; a new value needs a migration widening the {@code CHECK}.
 */
public enum Resolution {

    /** One measurement, at the instant it was taken. */
    RAW,

    /** The mean of one UTC hour of {@link #RAW} samples, timestamped at the hour's start. */
    HOUR;

    /** Returns the start of the UTC hour an instant falls in, the {@code at} of its {@link #HOUR} row. */
    public static Instant hourOf(final Instant at) {
        return at.truncatedTo(ChronoUnit.HOURS);
    }
}
