package eu.nordtal.season.database.online;

import java.time.Duration;
import java.time.InstantSource;
import java.util.Map;
import javax.sql.DataSource;

/**
 * Stores how many players are on each Minecraft-facing subject. The proxy writes it for steward.
 *
 * Each subject has one row, which is overwritten. The reader judges staleness from {@link OnlineCount#updated()}.
 */
public interface OnlineDirectory {

    /** How often the proxy rewrites every row. */
    Duration WRITE_INTERVAL = Duration.ofSeconds(10);

    /** Returns a directory that borrows {@code dataSource}. */
    static OnlineDirectory using(final DataSource dataSource, final InstantSource clock) {
        return new JdbiOnline(dataSource, clock);
    }

    /**
     * Replaces the count of every subject given. Other subjects keep their rows.
     *
     * @param counts the player count by subject, may be empty
     * @throws NullPointerException if the map or a key is null
     * @throws IllegalArgumentException if a value is negative
     */
    void write(Map<String, Integer> counts);

    /** Returns the rows by subject. A subject never written is absent, not zero. */
    Map<String, OnlineCount> current();
}
