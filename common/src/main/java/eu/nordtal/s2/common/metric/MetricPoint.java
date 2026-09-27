package eu.nordtal.s2.common.metric;

import java.time.Instant;

/**
 * One point of a curve, on its way out of the table.
 *
 * @param at         when it was measured, or for {@link Resolution#HOUR} the start of the UTC hour it averages
 * @param value      the measurement, or the mean of the hour
 * @param resolution which of the two this point is
 */
public record MetricPoint(Instant at, double value, Resolution resolution) {}
