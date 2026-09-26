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
 *
 * Every instant crosses as an {@link OffsetDateTime}: an {@code Instant} is rendered in the JVM zone and
 * read in the server's, which can shift history silently.
 */
@RegisterRowMapper(MetricPointMapper.class)
interface MetricDao {

    /**
     * Writes one sweep of measurements, as a JDBC batch.
     *
     * <b>{@code ON CONFLICT DO NOTHING} and not {@code DO UPDATE}</b>
     *
     * The primary key is (subject, metric, resolution, at), so a replayed sweep collides with
     * itself exactly. Keeping what is there rather than overwriting it is the stronger of the two
     * promises: a measurement at an instant is a fact, and a collector that has just restarted and
     * is re-sending its last sweep must not be able to revise history, only to fail to add to it.
     *
     * It is also what keeps {@link #compactInto} honest. If a raw sample could be rewritten
     * after its hour had been averaged, the mean and the rows behind it would disagree, and the
     * rows are deleted shortly afterwards - so the disagreement would outlive the evidence.
     *
     * @param samples one per row; {@code :at} and the rest are bound off the record's accessors
     * @return one count per statement, 1 where a row was written and 0 where the key was taken
     */
    @SqlBatch("""
            INSERT INTO metric_sample (subject, metric, resolution, at, value)
            VALUES (:subject, :metric, 'RAW', :at, :value)
            ON CONFLICT (subject, metric, resolution, at) DO NOTHING
            """)
    int[] record(@BindMethods Iterable<BoundSample> samples);

    /**
     * One series over a window, across the seam between the two resolutions.
     *
     * <b>Two index scans and a UNION ALL, rather than one scan with an OR in it</b>
     *
     * Both halves name {@code subject}, {@code metric} and {@code resolution} by equality and then
     * bound {@code at} - which is the primary key read forwards, twice. The obvious single query
     * with {@code (resolution = 'RAW' OR (resolution = 'HOUR' AND ...))} cannot use the index that
     * way: {@code resolution} sits between the equalities and the range, so the range on {@code at}
     * stops being an index condition and every row of the series gets read and filtered instead.
     *
     * <b>The seam is the <i>hour</i> of the oldest raw sample</b>
     *
     * The scalar subquery is a {@code min()} over the same three equality columns, so it is one
     * index probe. {@code coalesce(..., 'infinity')} is the case that reads oddly and matters most:
     * a series with no raw rows at all - everything already compacted - must return every hourly
     * point rather than none.
     *
     * <b>The bucket around that {@code min()} is not decoration.</b> An hourly {@code at} is
     * always an exact hour start; a raw sample almost never is. Compared against the raw instant,
     * the mean for ten o'clock passes {@code at < 10:00:07} and comes back <i>next to the very
     * samples it was averaged from</i> - one hour drawn twice, once flattened and once not. The
     * only arrangement that escaped it was a series whose oldest raw sample sat exactly on the
     * boundary, which is what the first test of this happened to build. Truncated to its hour, the
     * comparison is {@code 10:00 < 10:00}, which is false, and the raw rows answer alone.
     *
     * The bucket expression is {@link #compactInto}'s, character for character, and for the
     * reason given there: on a {@code timestamptz}, {@code date_trunc('hour', ...)} is neither
     * immutable nor UTC.
     *
     * An hour that has both a mean and its raw rows is therefore answered from the raw rows.
     * That is the overlap between a compaction and the delete behind it, and it is the state this
     * query exists to get right.
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
     * Averages every raw sample before {@code cut} into its UTC hour and writes the means.
     *
     * <b>Running it twice writes nothing the second time</b>
     *
     * {@code ON CONFLICT DO NOTHING} on the same primary key: an hour that already has its mean
     * keeps it. That is what makes the operation safe to repeat after a crash, and it is also why
     * nothing can be double-counted - {@code avg()} always reads {@code resolution = 'RAW'} rows,
     * never other means, so a mean is never folded into a mean.
     *
     * The consequence, stated plainly: an hourly mean is written <b>once</b> and is never
     * revised. A raw sample that arrives for an hour already averaged does not change it. That is
     * why {@code cut} must be an hour boundary the collector is finished with - see
     * {@link MetricDirectory#compact}.
     *
     * <b>The bucket expression is the migration's, character for character</b>
     *
     * {@code to_timestamp(floor(extract(epoch FROM at) / 3600) * 3600)} is also the table's
     * alignment CHECK. If the two ever drifted apart, this INSERT would be refused by the
     * constraint - which is the failure one wants, but only because they are written the same way
     * on purpose. {@code date_trunc('hour', ...)} is not used, for the reason the migration gives:
     * on a {@code timestamptz} it is not immutable, and it is not UTC.
     *
     * @param cut exclusive, and an exact UTC hour - {@link JdbiMetrics} rounds it down before
     *            calling
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
     * A join against the hourly rows rather than a correlated {@code EXISTS}, which cannot use a hash join.
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

    /** A {@link MetricSample} with its instant as an {@link OffsetDateTime}, one object per batch statement. */
    record BoundSample(String subject, String metric, OffsetDateTime at, double value) {}
}
