package eu.nordtal.s2.common.network;

import javax.sql.DataSource;

/**
 * One read of what the network is currently doing, for anything that has to say so out loud.
 *
 * <p>Shared rather than copied: the MOTD and the bot's channel name say the same things, and two
 * queries computing "the same" numbers is how two public surfaces start disagreeing.
 *
 * <p>It reads tables it does not own ({@code hg_*}, {@code smp_*}), which is the accepted trade
 * against those plugins pushing their state to two consumers; {@link SnapshotDao} names the
 * migrations it depends on.
 *
 * <p>An unreachable database throws rather than returning {@link NetworkSnapshot#EMPTY}, because
 * "no game is running" and "I could not ask" have to be distinguishable - both callers keep their
 * last good snapshot instead of blanking their surface.
 */
public interface SnapshotDirectory {

    /**
     * Reads over a connection pool the caller owns.
     *
     * @param dataSource the pool - the proxy's, or the bot's
     * @return a directory over that pool; it owns nothing and there is nothing to close
     */
    static SnapshotDirectory using(final DataSource dataSource) {
        return new JdbiSnapshotDirectory(dataSource);
    }

    /**
     * @return the counts as of right now; never {@code null}, and {@link NetworkSnapshot#EMPTY}
     *         only if the query somehow returned no row at all
     * @throws RuntimeException if the database cannot be reached - see the class documentation
     */
    NetworkSnapshot snapshot();
}
