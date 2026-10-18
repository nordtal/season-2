package eu.nordtal.season.database.update;

import java.time.Instant;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * What the standby proxy last said it holds, which steward reads before it stops {@code proxy-standby}.
 * The caller decides how old is too old.
 */
public interface StandbyDirectory {

    /** Returns a directory that borrows {@code dataSource}. */
    static StandbyDirectory using(final DataSource dataSource) {
        return new JdbiStandby(dataSource);
    }

    /** Returns the last heartbeat of the standby, or empty if none was written. Empty is not zero. */
    Optional<Standby> current();

    /**
     * One heartbeat of the standby proxy.
     *
     * @param players the players it holds
     * @param updated when it wrote the heartbeat
     */
    record Standby(int players, Instant updated) {

        public boolean isFresh(final Instant now, final java.time.Duration within) {
            return !updated.isBefore(now.minus(within));
        }
    }
}
