package eu.nordtal.s2.common.metric;

import java.time.OffsetDateTime;
import java.util.List;
import org.jdbi.v3.sqlobject.config.RegisterRowMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlBatch;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

/**
 * The SQL surface of the time-series table; {@link MetricDirectory} is the API.
 * Every instant crosses as an {@link OffsetDateTime}, since an {@code Instant} can be shifted by the JVM or server
 * zone.
 */
@RegisterRowMapper(MetricPointMapper.class)
interface MetricDao {

    /**
     * Writes one sweep of measurements as a JDBC batch, keeping any row already at the same key.
     *
     * @return one count per statement, 1 where a row was written and 0 where the key was taken
     */
    @SqlBatch("""
            INSERT INTO metric_sample (subject, metric, resolution, at, value)
            VALUES (:subject, :metric, 'RAW', :at, :value)
            ON CONFLICT (subject, metric, resolution, at) DO NOTHING
            """)
    int[] record(@BindMethods Iterable<BoundSample> samples);

    /**
     * Returns one series over a window: raw samples from the oldest raw sample's hour on, hourly means before.
     * Two index scans and a UNION ALL, since an OR would stop the range on {@code at} being indexed.
     *
     * @param from inclusive
     * @param to   exclusive
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
     * Averages every raw sample before {@code cut} into its UTC hour and writes each mean once.
     * The bucket is the migration's alignment CHECK verbatim, since {@code date_trunc} is not immutable.
     *
     * @param cut exclusive, and an exact UTC hour
     * @return how many hourly rows were written
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
              AND at < :cut
            GROUP BY subject, metric, to_timestamp(floor(extract(epoch FROM at) / 3600) * 3600)
            ON CONFLICT (subject, metric, resolution, at) DO NOTHING
            """)
    int compactInto(@Bind("cut") OffsetDateTime cut);

    /**
     * Deletes raw samples before {@code cut} whose hour already has a mean.
     *
     * @param cut exclusive, and an exact UTC hour
     * @return how many raw rows went
     */
    @SqlUpdate("""
            DELETE FROM metric_sample raw
            USING (SELECT subject, metric, at
                   FROM metric_sample
                   WHERE resolution = 'HOUR'
                     AND at < :cut) hourly
            WHERE raw.resolution = 'RAW'
              AND raw.at < :cut
              AND hourly.subject = raw.subject
              AND hourly.metric = raw.metric
              AND hourly.at = to_timestamp(floor(extract(epoch FROM raw.at) / 3600) * 3600)
            """)
    int forget(@Bind("cut") OffsetDateTime cut);

    /** A {@link MetricSample} with its instant as an {@link OffsetDateTime}. */
    record BoundSample(String subject, String metric, OffsetDateTime at, double value) {}
}
