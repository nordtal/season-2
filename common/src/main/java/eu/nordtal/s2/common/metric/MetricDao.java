package eu.nordtal.s2.common.metric;

import org.jdbi.v3.sqlobject.config.RegisterRowMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlBatch;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * The whole SQL surface of the time-series table, as a JDBI SqlObject interface - the same style as
 * {@code UpdateDao} and {@code AccessDao}.
 * <p>
 * Package-private on purpose: {@link MetricDirectory} is the API, this is how it is implemented, and
 * no consumer ever holds a {@code Jdbi} or a DAO of ours.
 * </p>
 * <h2>Why every instant crosses this boundary as an {@link OffsetDateTime} and not an
 * {@code Instant}</h2>
 * An {@code Instant} bound through JDBC becomes {@code setTimestamp(java.sql.Timestamp)}, which
 * pgjdbc renders in the JVM's default zone and sends with no type attached; the server then reads
 * it back into a {@code timestamptz} using the <em>server's</em> TimeZone. The two agree on this
 * host and would agree in almost every test - which is exactly what makes the disagreement, when it
 * comes, arrive silently and shift a month of history by an hour. An {@code OffsetDateTime} carries
 * its offset onto the wire, so there is nothing for either side to assume.
 */
@RegisterRowMapper(MetricPointMapper.class)
interface MetricDao {

    /**
     * Writes one sweep of measurements, as a JDBC batch.
     *
     * <h2>{@code ON CONFLICT DO NOTHING} and not {@code DO UPDATE}</h2>
     * The primary key is (subject, metric, resolution, at), so a replayed sweep collides with
     * itself exactly. Keeping what is there rather than overwriting it is the stronger of the two
     * promises: a measurement at an instant is a fact, and a collector that has just restarted and
     * is re-sending its last sweep must not be able to revise history, only to fail to add to it.
     *
     * <p>It is also what keeps {@link #compactInto} honest. If a raw sample could be rewritten
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
     * <h2>Two index scans and a UNION ALL, rather than one scan with an OR in it</h2>
     * Both halves name {@code subject}, {@code metric} and {@code resolution} by equality and then
     * bound {@code at} - which is the primary key read forwards, twice. The obvious single query
     * with {@code (resolution = 'RAW' OR (resolution = 'HOUR' AND ...))} cannot use the index that
     * way: {@code resolution} sits between the equalities and the range, so the range on {@code at}
     * stops being an index condition and every row of the series gets read and filtered instead.
     *
     * <h2>The seam is the oldest raw sample there is</h2>
     * The scalar subquery is a {@code min()} over the same three equality columns, so it is one
     * index probe. {@code coalesce(..., 'infinity')} is the case that reads oddly and matters most:
     * a series with no raw rows at all - everything already compacted - must return every hourly
     * point rather than none.
     *
     * <p>An hour that has both a mean and its raw rows is therefore answered from the raw rows: its
     * hourly {@code at} is at or after the oldest raw sample, so the second half of the union does
     * not return it. That is the overlap between a compaction and the delete behind it, and it is
     * the state this query exists to get right.
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
              AND at < coalesce((SELECT min(at)
                                 FROM metric_sample
                                 WHERE subject = :subject
                                   AND metric = :metric
                                   AND resolution = 'RAW'), 'infinity'::timestamptz)
            ORDER BY at
            """)
    List<MetricPoint> range(@Bind("subject") String subject,
                            @Bind("metric") String metric,
                            @Bind("from") OffsetDateTime from,
                            @Bind("to") OffsetDateTime to);

    /**
     * Averages every raw sample before {@code cut} into its UTC hour and writes the means.
     *
     * <h2>Running it twice writes nothing the second time</h2>
     * {@code ON CONFLICT DO NOTHING} on the same primary key: an hour that already has its mean
     * keeps it. That is what makes the operation safe to repeat after a crash, and it is also why
     * nothing can be double-counted - {@code avg()} always reads {@code resolution = 'RAW'} rows,
     * never other means, so a mean is never folded into a mean.
     *
     * <p>The consequence, stated plainly: an hourly mean is written <b>once</b> and is never
     * revised. A raw sample that arrives for an hour already averaged does not change it. That is
     * why {@code cut} must be an hour boundary the collector is finished with - see
     * {@link MetricDirectory#compact}.
     *
     * <h2>The bucket expression is the migration's, character for character</h2>
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
     * <p>The match against the hourly rows is the whole safety of this statement. Without it the
     * delete would be "old enough", and a compaction that never ran - or ran and failed - would be
     * indistinguishable from one that worked, right up until somebody asked for the month that is
     * now gone.
     *
     * <h2>A join and not a correlated {@code EXISTS}, and this one was measured</h2>
     * The obvious spelling is {@code AND EXISTS (SELECT 1 FROM metric_sample hourly WHERE ... AND
     * hourly.at = <bucket of raw.at>)}. It is correct and it is unusable: the join condition
     * contains an expression of the outer row, so no hash or merge join is available and the
     * planner is left with a nested loop over every raw row. On 950 400 rows - one month of this
     * host's eleven series, the real number - it had not finished after <b>seven minutes</b> and
     * was killed (PostgreSQL 17.11, 2026-09-12). The nightly job would have held that transaction
     * open for the whole of it.
     *
     * <p>Written as a join against the small side, the same work is a hash join: 7 920 hourly rows
     * hashed once, one scan of the raw rows probing it. Measured on the same data,
     * <b>4.2 seconds</b> for all 950 400 deletions.
     *
     * <p>It is a semi-join in effect and not a multiplying one: the subquery's three columns are
     * the primary key with {@code resolution} fixed, so a raw row matches at most one hourly row.
     * A {@code DELETE} removes a row once in any case, but the property is worth stating - it is
     * what makes the returned count the number of rows that went.
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

    /**
     * A {@link MetricSample} with its instant already turned into an {@link OffsetDateTime}, which
     * is the only form that crosses to pgjdbc without a zone being assumed on the way. See the
     * note on this interface.
     *
     * <p>A record rather than four parallel lists because {@code @SqlBatch} binds one object per
     * statement, and because four lists that must stay the same length is the bug that gets written
     * the first time somebody filters one of them.
     */
    record BoundSample(String subject, String metric, OffsetDateTime at, double value) {
    }
}
