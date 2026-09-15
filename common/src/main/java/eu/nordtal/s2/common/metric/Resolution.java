package eu.nordtal.s2.common.metric;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * How much time one row of {@code metric_sample} stands for.
 *
 * <p>The names are the strings in the column - the same arrangement {@code UpdateKind} has with
 * {@code update_request.kind}, and it carries the same standing obligation: a value added here
 * without a migration widening the {@code CHECK} compiles, passes every unit test, and is refused
 * by a real database at the moment somebody writes one.
 */
public enum Resolution {

    /** One measurement, at the instant it was taken. Written every 30 seconds by the collector. */
    RAW,

    /**
     * The arithmetic mean of one UTC hour of {@link #RAW} samples, timestamped at the start of that
     * hour. Written by {@link MetricDirectory#compact(Instant)} once the raw rows are old enough to
     * go, which is how a month of samples becomes a thirtieth of itself without losing the year.
     */
    HOUR;

    /**
     * The start of the UTC hour an instant falls in - the {@code at} an {@link #HOUR} row for it
     * carries.
     *
     * <p>UTC and not a local hour, because that is what the database does: the migration's
     * alignment check is {@code floor(epoch / 3600)}, which has no time zone in it, and computing
     * the bucket any other way here would put Java and PostgreSQL a quarter of an hour apart in
     * the zones that have such offsets.
     *
     * @param at any instant
     * @return the instant at the start of its UTC hour
     */
    public static Instant hourOf(final Instant at) {
        return at.truncatedTo(ChronoUnit.HOURS);
    }
}
