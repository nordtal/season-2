package eu.nordtal.s2.common.metric;

import java.time.Instant;

/**
 * One point of a curve, on its way out of the table.
 *
 * <p>Not the same shape as {@link MetricSample} on purpose, and the difference is the two fields
 * that move.
 *
 * <p>The subject and the metric are gone: {@link MetricDirectory#range} was asked for one series,
 * so repeating its name on every one of up to 86 400 points is a string per point that the caller
 * already knows.
 *
 * <p>{@link #resolution()} appears instead, because a range that crosses the compaction boundary
 * returns points of both kinds and the difference is visible to whoever reads the graph: to the
 * left of the seam each point is an hour's mean, so a spike that lasted two minutes is a bump a
 * thirtieth of its height, and the axis has to say so. Dropping the field would make the curve
 * lie quietly rather than loudly.
 *
 * @param at         for {@link Resolution#RAW}, when it was measured; for {@link Resolution#HOUR},
 *                   the start of the UTC hour it averages
 * @param value      the measurement, or the mean of the hour
 * @param resolution which of the two this point is
 */
public record MetricPoint(Instant at, double value, Resolution resolution) {}
