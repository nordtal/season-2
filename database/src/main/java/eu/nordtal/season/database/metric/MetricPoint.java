package eu.nordtal.season.database.metric;

import java.time.Instant;

/**
 * One point of a curve.
 *
 * @param at the time measured, or the start of the UTC hour for {@link Resolution#HOUR}
 * @param value the measurement, or the mean of the hour
 */
public record MetricPoint(Instant at, double value, Resolution resolution) {}
