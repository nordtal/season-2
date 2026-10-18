package eu.nordtal.season.database.metric;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.jdbi.v3.sqlobject.config.RegisterRowMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlBatch;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

/**
 * The SQL of the time-series table. {@link MetricDirectory} is its API.
 * Instants cross as {@link OffsetDateTime}, as the JVM or server zone can shift an {@code Instant}.
 */
@RegisterRowMapper(MetricPointMapper.class)
interface MetricDao {

    /**
     * Writes a sweep as a JDBC batch and keeps a row at an existing key.
     *
     * @return one count per statement: 1 if written, 0 if the key existed
     */
    @SqlBatch("""
            INSERT INTO metric_sample (subject, metric, resolution, at, value)
            VALUES (:subject, :metric, 'RAW', :at, :value)
            ON CONFLICT (subject, metric, resolution, at) DO NOTHING
            """)
    int[] record(@BindMethods Iterable<BoundSample> samples);

    /**
     * Returns a series over a window: raw samples from the oldest raw sample's hour on, hourly means before.
     * Two index scans and a UNION ALL keep the range on {@code at} indexed, which an OR would not.
     *
     * @param from inclusive
     * @param to exclusive
     */
    @SqlQuery("""
            SELECT at, value, resolution
            FROM metric_sample
            WHERE subject = :subject
              AND metric = :metric
              AND resolution = 'RAW'
              AND at >= :from
              AND at < :to
            UNION ALL
            SELECT at, value, resolution
            FROM metric_sample
            WHERE subject = :subject
              AND metric = :metric
              AND resolution = 'HOUR'
              AND at >= :from
              AND at < :to
              AND at < coalesce((SELECT to_timestamp(floor(extract(epoch FROM min(at)) / 3600) * 3600)
                                 FROM metric_sample
                                 WHERE subject = :subject
                                   AND metric = :metric
                                   AND resolution = 'RAW'), 'infinity'::timestamptz)
            ORDER BY at
            """)
    List<MetricPoint> range(
            @Bind("subject") String subject,
            @Bind("metric") String metric,
            @Bind("from") OffsetDateTime from,
            @Bind("to") OffsetDateTime to);

    /**
     * Returns the instant of the oldest raw sample in {@code [from, to)}, or nothing.
     *
     * @param from inclusive
     * @param to exclusive
     */
    @SqlQuery("""
            SELECT min(at)
            FROM metric_sample
            WHERE resolution = 'RAW'
              AND at >= :from
              AND at < :to
            """)
    Optional<OffsetDateTime> oldestRaw(@Bind("from") OffsetDateTime from, @Bind("to") OffsetDateTime to);

    /**
     * Averages the raw samples of the UTC hour at {@code hour} and writes each mean once.
     * The bucket repeats the migration's alignment CHECK, as {@code date_trunc} is not immutable.
     *
     * @param hour inclusive, an exact UTC hour
     * @return the hourly rows written
     */
    @SqlUpdate("""
            INSERT INTO metric_sample (subject, metric, resolution, at, value)
            SELECT subject,
                   metric,
                   'HOUR',
                   to_timestamp(floor(extract(epoch FROM at) / 3600) * 3600),
                   avg(value)
            FROM metric_sample
            WHERE resolution = 'RAW'
              AND at >= :hour
              AND at < :hour + interval '1 hour'
            GROUP BY subject, metric, to_timestamp(floor(extract(epoch FROM at) / 3600) * 3600)
            ON CONFLICT (subject, metric, resolution, at) DO NOTHING
            """)
    int compactHour(@Bind("hour") OffsetDateTime hour);

    /**
     * Deletes the raw samples of the UTC hour at {@code hour} that already have a mean.
     *
     * @param hour inclusive, an exact UTC hour
     * @return the raw rows deleted
     */
    @SqlUpdate("""
            DELETE FROM metric_sample raw
            USING (SELECT subject, metric, at
                   FROM metric_sample
                   WHERE resolution = 'HOUR'
                     AND at = :hour) hourly
            WHERE raw.resolution = 'RAW'
              AND raw.at >= :hour
              AND raw.at < :hour + interval '1 hour'
              AND hourly.subject = raw.subject
              AND hourly.metric = raw.metric
            """)
    int forgetHour(@Bind("hour") OffsetDateTime hour);

    /** A {@link MetricSample} with an {@link OffsetDateTime}. */
    record BoundSample(String subject, String metric, OffsetDateTime at, double value) {}
}
