package eu.nordtal.s2.common.update;

import java.time.Instant;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * What the standby proxy is holding, read by steward-worker.
 *
 * <b>Why the worker reads a table instead of asking the standby</b>
 *
 * The one question the run has to answer before it stops {@code proxy-standby} is "is anybody still
 * on it". Asking the standby directly would mean a second process answering an HTTP call at the one
 * moment it is least able to - it is being stopped - and it would need an API that exists for this
 * single sentence. The standby already writes the number: {@code proxy_standby_state} is one row
 * with a count and the instant it was written, and V31 says in as many words that it exists for a
 * reader on the worker's side.
 *
 * <b>Nobody decides staleness here</b>
 *
 * {@link #current()} hands back what the row says and when it was said, and nothing else. A standby
 * that was killed mid-swap leaves its last count standing for ever, so a caller that only looked at
 * {@code players} would either wait on a number nobody is updating or act on one that is minutes
 * old - which is exactly why the migration keeps {@code updated_at}. How old is too old is the
 * caller's call, the same split {@code OnlineDirectory} draws for the same reason.
 */
public interface StandbyDirectory {

    /**
     * @param dataSource the pool the caller already owns
     * @return a directory over that pool; it owns nothing and there is nothing to close
     */
    static StandbyDirectory using(final DataSource dataSource) {
        return new JdbiStandby(dataSource);
    }

    /**
     * @return the standby's last heartbeat, or empty when it has never written one - a deployment
     *         where no standby has ever run. Empty is <b>not</b> zero: "nobody is on it" and
     *         "nothing has ever said" are the two answers a stop has to tell apart
     */
    Optional<Standby> current();

    /**
     * One heartbeat of the standby proxy.
     *
     * @param players how many it was holding
     * @param updated when it said so
     */
    record Standby(int players, Instant updated) {

        /** @return whether this heartbeat is recent enough to act on */
        public boolean isFresh(final Instant now, final java.time.Duration within) {
            return !updated.isBefore(now.minus(within));
        }
    }
}
