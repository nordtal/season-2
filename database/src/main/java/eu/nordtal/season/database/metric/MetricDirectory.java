package eu.nordtal.season.database.metric;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;

/**
 * The time series behind Steward's start page, written and read by steward.
 *
 * Raw samples are compacted into hourly means and then forgotten.
 */
public interface MetricDirectory {

    /** How often the collector samples. */
    Duration SAMPLE_INTERVAL = Duration.ofSeconds(30);

    /** How long a raw sample survives before it is compacted into its hour. A longer value grows every backup. */
    Duration RAW_RETENTION = Duration.ofDays(30);

    /** Returns a directory over {@code dataSource}, holding no resource of its own. */
    static MetricDirectory using(final DataSource dataSource) {
        return new JdbiMetrics(dataSource);
    }

    /**
     * Writes a batch of measurements at {@link Resolution#RAW}; an empty list does nothing.
     * Idempotent per key: a sample already present for the same subject, metric and instant is kept and the new one
     * dropped.
     */
    void record(List<MetricSample> samples);

    /**
     * Returns one series over a window, oldest first, which is what Steward draws.
     * From the oldest raw sample's hour on, raw samples answer; before it, hourly means.
     *
     * @param from inclusive
     * @param to   exclusive
     */
    List<MetricPoint> range(String subject, String metric, Instant from, Instant to);

    /**
     * Turns raw samples before the start of the given instant's UTC hour into hourly means; steward only.
     * Nothing is deleted, an hour with its mean is left alone, and each hour is its own statement, oldest first.
     *
     * @return how many hourly rows were written
     */
    int compact(Instant olderThan);

    /**
     * Deletes raw samples before the given instant's UTC hour whose hour already has a mean; steward only.
     * Each hour is its own statement, oldest first.
     *
     * @return how many raw rows were deleted
     */
    int forget(Instant olderThan);
}
