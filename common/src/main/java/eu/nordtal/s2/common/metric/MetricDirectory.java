package eu.nordtal.s2.common.metric;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * The time series behind Steward's start page - written by steward-worker, read by steward-ui.
 *
 * <p>It lives in {@code :common} and not in the worker for the reason §10c settles: the interface
 * reads its curves <b>out of PostgreSQL</b> and not out of Docker, so the two programs are a writer
 * and a reader of one table and both need the same shapes. Docker's per-container stats answer
 * "now" and keep no history at all; a page drawing curves from them would draw one point and call
 * it a line.
 *
 * <p>Nothing here names JDBI, HikariCP or Docker. The factory takes a {@link DataSource} and every
 * process hands in the pool it already owns - the same arrangement
 * {@code eu.nordtal.s2.common.update.UpdateDirectory} has.
 *
 * <h2>What the table costs, because it decides the shape of everything below</h2>
 * Measured on this host on 2026-09-12: a sample every 30 seconds over eleven series is 2880 points
 * per day and series, about 32 000 rows a day, just under a million after 30 days, of the order of
 * 60 MB. And the table is inside the nightly backup, so raw samples kept forever would cost that
 * much again in every snapshot ever taken. Hence {@link #compact(Instant)} and
 * {@link #forget(Instant)}, which are not housekeeping but the reason the retention is affordable.
 */
public interface MetricDirectory {

    /**
     * How often the collector samples - 30 seconds, from §10c, and the number every size above is
     * derived from.
     *
     * <p>A constant rather than a setting: it is the denominator of the storage estimate written
     * into the migration, and a deployment that quietly halved it would quietly double the size of
     * every backup.
     */
    Duration SAMPLE_INTERVAL = Duration.ofSeconds(30);

    /**
     * How long a raw sample survives before it is compacted into its hour - 30 days, from §10c.
     *
     * <p>Deciding to keep raw samples longer is deciding that every future backup is larger. See
     * the comment at the top of {@code V17__metric_sample.sql}, which is the one place that says so
     * at length.
     */
    Duration RAW_RETENTION = Duration.ofDays(30);

    /**
     * @param dataSource the pool - the same one this process already reads the update inbox or the
     *                   phase through
     * @return a directory over that pool. Holds no resource of its own, so there is nothing to close
     */
    static MetricDirectory using(final DataSource dataSource) {
        return new JdbiMetrics(dataSource);
    }

    /**
     * Writes a batch of measurements, all at {@link Resolution#RAW}.
     *
     * <p>A batch and not one call per sample because one sweep produces eleven of them at once, and
     * eleven round trips every 30 seconds is eleven round trips every 30 seconds forever.
     *
     * <p><b>Idempotent per key.</b> A sample already present for the same subject, metric and
     * instant is kept as it is and the new one is dropped - never counted twice and never used to
     * revise the old one. A measurement at an instant is a fact; a collector that has just come
     * back after a crash and is replaying its last sweep must be able to write it again without
     * thinking about it.
     *
     * @param samples what was measured. An empty list is allowed and does nothing
     * @throws NullPointerException if the list or any sample in it is {@code null}
     */
    void record(List<MetricSample> samples);

    /**
     * One series over a window, oldest first - what steward-ui draws.
     *
     * <h2>How a window that crosses the compaction boundary is answered</h2>
     * A window of a year contains both kinds of row: hourly means at the old end, raw samples at
     * the new end, and - during the days between a {@link #compact(Instant)} and the
     * {@link #forget(Instant)} that follows it - some hours that have <b>both</b>.
     *
     * <p>The answer is one point per instant, chosen by a single seam: <b>the oldest raw sample
     * still in the table</b>. From that instant on the raw samples are the series; before it the
     * hourly means are. An hour that still has its raw rows is therefore drawn from them and its
     * mean is ignored, which is what makes the overlap invisible instead of doubled.
     *
     * <p>That is exactly the invariant {@link #forget(Instant)} maintains - it only ever deletes
     * the oldest raw rows, and only ones whose hour has already been averaged - so the seam is a
     * single instant and not a set of holes. The alternative considered was asking, per hourly row,
     * whether any raw row exists in its hour: the same answer in the states that occur, a
     * correlated existence check per point, and an answer that silently disagrees with itself if
     * the raw rows are ever not a contiguous tail. One scalar minimum is cheaper and states the
     * invariant out loud.
     *
     * <p>Each point says which kind it is ({@link MetricPoint#resolution()}), because a mean over
     * an hour flattens a two-minute spike to a thirtieth of its height and the axis has to say so.
     *
     * @param subject what was measured
     * @param metric  which number
     * @param from    inclusive
     * @param to      exclusive - so two adjacent windows do not both contain the point between them
     * @return the points, oldest first; empty when the series has none in that window
     */
    List<MetricPoint> range(String subject, String metric, Instant from, Instant to);

    /**
     * Turns raw samples older than the given instant into hourly means. <b>Only steward-worker
     * calls this.</b>
     *
     * <p>Nothing is deleted here - {@link #forget(Instant)} does that, afterwards. The two are
     * separate so that a compaction which computed nonsense can be looked at while the rows it was
     * computed from are still there.
     *
     * <h2>Whole hours only</h2>
     * {@code olderThan} is rounded <em>down</em> to the start of its UTC hour, and only raw samples
     * before that point are averaged. The hour {@code olderThan} falls in is never touched, because
     * half of it may not have been measured yet - and writing the mean of half an hour would be
     * permanent: the row is written once and never revised, so the rest of the hour arriving later
     * would have nowhere to go.
     *
     * <h2>Safe to run twice</h2>
     * An hour that already has its mean is left exactly as it is, so a second run over the same
     * window writes nothing, changes nothing and returns zero. Nothing is double-counted, because
     * the mean is always computed from the raw rows and never from other means.
     *
     * @param olderThan raw samples before the start of this instant's UTC hour are compacted.
     *                  Normally {@code Instant.now().minus(RAW_RETENTION)}
     * @return how many hourly rows were written - zero on a second run over the same window
     */
    int compact(Instant olderThan);

    /**
     * Deletes raw samples older than the given instant <b>that have already been compacted</b>.
     * <b>Only steward-worker calls this.</b>
     *
     * <p>The condition is not "old" but "old and its hour has a mean". A raw row whose hour was
     * never averaged - because {@link #compact(Instant)} has not run, or failed halfway - stays.
     * Deleting on age alone would make a failed compaction indistinguishable from a successful one
     * afterwards, and the thing lost would be a month of history nobody could get back.
     *
     * <p>Rounded down to the UTC hour like {@link #compact(Instant)}, so the two ends of the
     * retention always move in whole hours and a half-deleted hour cannot appear on the graph
     * beside its own mean.
     *
     * @param olderThan raw samples before the start of this instant's UTC hour are candidates
     * @return how many raw rows were deleted
     */
    int forget(Instant olderThan);
}
