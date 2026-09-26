package eu.nordtal.s2.common.metric;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;

/**
 * The time series behind Steward's start page, written by steward-worker and read by steward-ui.
 *
 * Raw samples are compacted into hourly means and then forgotten, which keeps the table and the
 * nightly backup affordable.
 */
public interface MetricDirectory {

    /** How often the collector samples; a constant, as the storage estimate is derived from it. */
    Duration SAMPLE_INTERVAL = Duration.ofSeconds(30);

    /**
     * How long a raw sample survives before it is compacted into its hour - 30 days, from §10c.
     *
     * Deciding to keep raw samples longer is deciding that every future backup is larger. See
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
     * A batch and not one call per sample because one sweep produces eleven of them at once, and
     * eleven round trips every 30 seconds is eleven round trips every 30 seconds forever.
     *
     * <b>Idempotent per key.</b> A sample already present for the same subject, metric and
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
     * <b>How a window that crosses the compaction boundary is answered</b>
     *
     * A window of a year contains both kinds of row: hourly means at the old end, raw samples at
     * the new end, and - during the days between a {@link #compact(Instant)} and the
     * {@link #forget(Instant)} that follows it - some hours that have <b>both</b>.
     *
     * The answer is one point per instant, chosen by a single seam: <b>the oldest raw sample
     * still in the table</b>. From that instant on the raw samples are the series; before it the
     * hourly means are. An hour that still has its raw rows is therefore drawn from them and its
     * mean is ignored, which is what makes the overlap invisible instead of doubled.
     *
     * That is exactly the invariant {@link #forget(Instant)} maintains - it only ever deletes
     * the oldest raw rows, and only ones whose hour has already been averaged - so the seam is a
     * single instant and not a set of holes. The alternative considered was asking, per hourly row,
     * whether any raw row exists in its hour: the same answer in the states that occur, a
     * correlated existence check per point, and an answer that silently disagrees with itself if
     * the raw rows are ever not a contiguous tail. One scalar minimum is cheaper and states the
     * invariant out loud.
     *
     * Each point says which kind it is ({@link MetricPoint#resolution()}), because a mean over
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
     * Turns raw samples before the start of the given instant's UTC hour into hourly means; worker only.
     *
     * Nothing is deleted here. An hour that already has its mean is left alone, so a second run writes nothing.
     *
     * @param olderThan normally {@code Instant.now().minus(RAW_RETENTION)}
     * @return how many hourly rows were written
     */
    int compact(Instant olderThan);

    /**
     * Deletes raw samples older than the given instant <b>that have already been compacted</b>.
     * <b>Only steward-worker calls this.</b>
     *
     * The condition is not "old" but "old and its hour has a mean". A raw row whose hour was
     * never averaged - because {@link #compact(Instant)} has not run, or failed halfway - stays.
     * Deleting on age alone would make a failed compaction indistinguishable from a successful one
     * afterwards, and the thing lost would be a month of history nobody could get back.
     *
     * Rounded down to the UTC hour like {@link #compact(Instant)}, so the two ends of the
     * retention always move in whole hours and a half-deleted hour cannot appear on the graph
     * beside its own mean.
     *
     * @param olderThan raw samples before the start of this instant's UTC hour are candidates
     * @return how many raw rows were deleted
     */
    int forget(Instant olderThan);
}
