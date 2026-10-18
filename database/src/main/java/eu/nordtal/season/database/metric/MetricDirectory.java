package eu.nordtal.season.database.metric;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;

/**
 * Stores and reads the time series of Steward's start page. Only steward uses it.
 *
 * Raw samples are compacted into hourly means and then deleted.
 */
public interface MetricDirectory {

    /** How often the collector samples. */
    Duration SAMPLE_INTERVAL = Duration.ofSeconds(30);

    /** How long a raw sample lives before compaction. A longer value grows every backup. */
    Duration RAW_RETENTION = Duration.ofDays(30);

    /** Returns a directory that borrows {@code dataSource}. */
    static MetricDirectory using(final DataSource dataSource) {
        return new JdbiMetrics(dataSource);
    }

    /** Writes measurements at {@link Resolution#RAW}. A sample with the same subject, metric and instant is kept. */
    void record(List<MetricSample> samples);

    /**
     * Returns a series over a window, oldest first. Raw samples answer from the oldest raw sample's hour on.
     *
     * @param from inclusive
     * @param to exclusive
     */
    List<MetricPoint> range(String subject, String metric, Instant from, Instant to);

    /**
     * Turns raw samples before the UTC hour of {@code olderThan} into hourly means. Steward only.
     * It deletes nothing and skips an hour that has its mean.
     *
     * @return the hourly rows written
     */
    int compact(Instant olderThan);

    /**
     * Deletes raw samples before the UTC hour of {@code olderThan} whose hour has a mean. Steward only.
     *
     * @return the raw rows deleted
     */
    int forget(Instant olderThan);
}
