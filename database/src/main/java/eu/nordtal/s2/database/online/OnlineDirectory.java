package eu.nordtal.s2.database.online;

import java.time.Duration;
import java.time.InstantSource;
import java.util.Map;
import javax.sql.DataSource;

/**
 * How many players are on each Minecraft-facing subject, written by the proxy for steward.
 * One row per subject, overwritten; judging staleness from {@link OnlineCount#updated()} is the reader's job.
 */
public interface OnlineDirectory {

    /** How often proxy rewrites every row, which defines how fresh a count is. */
    Duration WRITE_INTERVAL = Duration.ofSeconds(10);

    /** Returns a directory over a pool the caller owns; there is nothing to close. */
    static OnlineDirectory using(final DataSource dataSource, final InstantSource clock) {
        return new JdbiOnline(dataSource, clock);
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
     * Returns every subject proxy has written a row for, keyed by subject; a subject never written is absent, not zero.
     */
    Map<String, OnlineCount> current();
}
