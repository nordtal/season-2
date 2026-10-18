package eu.nordtal.season.database.metric;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * How much time one row of {@code metric_sample} stands for.
 *
 * A new value needs a migration that widens the {@code CHECK}.
 */
public enum Resolution {

    RAW,

    /** The mean of the {@link #RAW} samples of one UTC hour, at the hour's start. */
    HOUR;

    public static Instant hourOf(final Instant at) {
        return at.truncatedTo(ChronoUnit.HOURS);
    }
}
