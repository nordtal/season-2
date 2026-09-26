package eu.nordtal.s2.common.online;

import java.time.Duration;
import java.util.Map;
import javax.sql.DataSource;

/**
 * How many players are on each Minecraft-facing subject, written by the proxy for steward-worker.
 *
 * One row per subject, overwritten, not a time series. A subject never written is absent, not zero;
 * judging staleness from {@link OnlineCount#updated()} is the reader's job.
 */
public interface OnlineDirectory {

    /** How often proxy rewrites every row; a constant, as it defines how fresh a count is. */
    Duration WRITE_INTERVAL = Duration.ofSeconds(10);

    /**
     * @param dataSource the pool the caller already owns - proxy's, or steward-worker's
     * @return a directory over that pool; it owns nothing and there is nothing to close
     */
    static OnlineDirectory using(final DataSource dataSource) {
        return new JdbiOnline(dataSource);
    }

    /**
     * Replaces the count for every subject given; other subjects keep their rows.
     *
     * @param counts subject to player count; an empty map is allowed and does nothing
     * @throws NullPointerException     if the map or a key in it is {@code null}
     * @throws IllegalArgumentException if a value is negative
     */
    void write(Map<String, Integer> counts);

    /**
     * @return every subject proxy has ever written a row for on this deployment, keyed by
     *         subject. A subject with no row at all - never written, or the table just migrated in -
     *         is simply not a key here; see the class documentation for why that is not the same as
     *         a zero
     */
    Map<String, OnlineCount> current();
}
