package eu.nordtal.s2.common.metric;

import java.time.Instant;
import java.util.Objects;

/**
 * One measurement, on its way into the table; an hourly mean is computed and never written from here.
 *
 * @param subject the literal {@code "host"} or a compose service name, never a container id
 * @param metric  which number, e.g. {@code "cpu"} or {@code "memory.bytes"}
 * @param at      when it was measured
 * @param value   the measurement, which must be finite
 */
public record MetricSample(String subject, String metric, Instant at, double value) {

    public MetricSample {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(metric, "metric");
        Objects.requireNonNull(at, "at");
        if (!Double.isFinite(value)) {
            // Refused here too, so the message names the series rather than a batch row.
            throw new IllegalArgumentException(
                    "A metric sample must be finite, and " + subject + '/' + metric + " is " + value);
        }
    }
}
