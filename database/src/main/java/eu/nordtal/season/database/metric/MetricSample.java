package eu.nordtal.season.database.metric;

import java.time.Instant;
import java.util.Objects;

/**
 * One measurement for the table.
 *
 * @param subject {@code "host"} or a compose service name, never a container id
 * @param value the measurement, which must be finite
 */
public record MetricSample(String subject, Metric metric, Instant at, double value) {

    public MetricSample {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(metric, "metric");
        Objects.requireNonNull(at, "at");
        if (!Double.isFinite(value)) {
            // Names the series, which a batch row would not.
            throw new IllegalArgumentException(
                    "A metric sample must be finite, and " + subject + '/' + metric.key() + " is " + value);
        }
    }
}
