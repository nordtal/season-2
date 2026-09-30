package eu.nordtal.s2.database.update;

import java.time.Instant;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * What the standby proxy last said it holds, read by steward-worker before it stops {@code proxy-standby}.
 *
 * It returns the count and when it was written; how old is too old is the caller's decision.
 */
public interface StandbyDirectory {

    /** Returns a directory over {@code dataSource}; it owns nothing. */
    static StandbyDirectory using(final DataSource dataSource) {
        return new JdbiStandby(dataSource);
    }

    /** Returns the standby's last heartbeat, or empty when none was ever written, which is not zero. */
    Optional<Standby> current();

    /**
     * One heartbeat of the standby proxy.
     *
     * @param players how many it was holding
     * @param updated when it said so
     */
    record Standby(int players, Instant updated) {

        /** Returns whether this heartbeat is recent enough to act on. */
        public boolean isFresh(final Instant now, final java.time.Duration within) {
            return !updated.isBefore(now.minus(within));
        }
    }
}
