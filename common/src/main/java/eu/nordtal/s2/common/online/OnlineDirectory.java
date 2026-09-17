package eu.nordtal.s2.common.online;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.Map;

/**
 * How many players are on each Minecraft-facing subject, right now - written by network-control,
 * read by steward-worker for {@code /api/services} (steward/86, feeding steward/81).
 *
 * <h2>One row per subject, overwritten - deliberately not {@code MetricDirectory}'s shape</h2>
 * {@code metric_sample} (V17) is a time series on purpose, because steward-ui draws curves from it.
 * steward/86 explicitly asked for the opposite: "keine Statistik und kein Verlauf" - a number for a
 * display, not a data store. So {@link #write} is an UPSERT per subject and {@code online_count}
 * never grows past one row per subject, the same shape {@code player_playtime} already uses for the
 * same reason: a write that repeats forever must not be a write that accumulates forever.
 *
 * <h2>A table, not a call to network-control</h2>
 * The other way to answer "how many players" is steward-worker asking network-control directly,
 * which was rejected: it would make {@code /api/services} depend on a second process answering in
 * time, and network-control is itself one of the four subjects, so the moment its own row would be
 * least reliable - while it restarts - is also the moment a live request to it is least likely to
 * come back. A table steward-worker already has a connection pool next to costs nothing new and
 * degrades the way the rest of the dashboard already does: a stale or missing row, not a failed
 * request. See {@code V22__online_count.sql} for the fuller version of this trade.
 *
 * <h2>"No number" is not {@code 0}, and this interface does not decide that on its own</h2>
 * {@link #current()} returns exactly the subjects that have a row - a subject network-control has
 * never written for is simply absent from the map, never present with a zero it did not measure.
 * Telling a live "nobody connected" 0 apart from a "network-control stopped writing a while ago" 0
 * needs {@link OnlineCount#updated()} held against a staleness cutoff, which is
 * {@code eu.nordtal.s2.steward.worker.api.ServicesApi}'s job and deliberately not this interface's -
 * the same split {@code ImageResult} draws between "what the registry answered" and "how a report
 * reads that answer".
 */
public interface OnlineDirectory {

    /**
     * How often network-control rewrites every row - ten seconds, the same figure
     * {@code network.yml#snapshot-refresh-seconds} already defaults to for the MOTD numbers this
     * reuses (see {@code eu.nordtal.s2.networkcontrol.ping.NetworkPing}).
     *
     * <p>A constant and not a setting, on purpose - the same call {@link
     * eu.nordtal.s2.common.metric.MetricDirectory#SAMPLE_INTERVAL} makes for the same reason: four
     * single-row UPSERTs every ten seconds is not a number worth exposing as a knob, and a
     * deployment that quietly changed it would quietly change what "how fresh is this count" means
     * for nobody in particular to have decided.
     */
    Duration WRITE_INTERVAL = Duration.ofSeconds(10);

    /**
     * @param dataSource the pool the caller already owns - network-control's, or steward-worker's
     * @return a directory over that pool; it owns nothing and there is nothing to close
     */
    static OnlineDirectory using(final DataSource dataSource) {
        return new JdbiOnline(dataSource);
    }

    /**
     * Replaces the count for every subject given, and only those - a subject not present in
     * {@code counts} keeps whatever row it already has.
     *
     * <p>Idempotent per subject: writing the same subject again, whatever the value, simply replaces
     * the row and its {@code updated} - there is no history to disturb.
     *
     * @param counts subject to player count; an empty map is allowed and does nothing
     * @throws NullPointerException     if the map or a key in it is {@code null}
     * @throws IllegalArgumentException if a value is negative
     */
    void write(Map<String, Integer> counts);

    /**
     * @return every subject network-control has ever written a row for on this deployment, keyed by
     *         subject. A subject with no row at all - never written, or the table just migrated in -
     *         is simply not a key here; see the class documentation for why that is not the same as
     *         a zero
     */
    Map<String, OnlineCount> current();
}
