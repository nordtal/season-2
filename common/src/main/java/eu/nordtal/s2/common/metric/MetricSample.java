package eu.nordtal.s2.common.metric;

import java.time.Instant;
import java.util.Objects;

/**
 * One measurement, on its way into the table.
 *
 * <p>There is no {@code resolution} on it, and that is deliberate: a sample is always something
 * that was <em>measured</em>, and an hourly mean is never measured - it is computed by
 * {@link MetricDirectory#compact(Instant)} out of the rows that were. Putting the field here would
 * let a caller write a mean it invented, which is the one row nobody could tell apart from a real
 * one afterwards.
 *
 * @param subject what was measured: the literal {@code "host"}, or a compose service name. Never a
 *                container id - a container is replaced on every deploy and the curve must not be
 * @param metric  which number, e.g. {@code "cpu"} or {@code "memory.bytes"}
 * @param at      when it was measured
 * @param value   the measurement. Must be finite; the database refuses NaN and the infinities,
 *                because {@code avg()} would carry one into an hourly mean that outlives the raw
 *                rows it came from
 */
public record MetricSample(String subject, String metric, Instant at, double value) {

    public MetricSample {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(metric, "metric");
        Objects.requireNonNull(at, "at");
        if (!Double.isFinite(value)) {
            // Refused here as well as by the CHECK, because a batch is one statement per sample and
            // the constraint violation would name a row rather than a series. This message names
            // the series, which is the thing whose collector is broken.
            throw new IllegalArgumentException(
                    "A metric sample must be finite, and " + subject + '/' + metric + " is " + value);
        }
    }
}
