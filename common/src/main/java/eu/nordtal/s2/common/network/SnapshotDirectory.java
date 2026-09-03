package eu.nordtal.s2.common.network;

import javax.sql.DataSource;

/**
 * One read of what the network is currently doing, for anything that has to say so out loud.
 *
 * <h2>Two readers, one query</h2>
 * This lived in {@code network-control} until 2026-09-03, where it fed the MOTD in the server
 * browser. The Discord bot now says the same things in a channel name, and a second query
 * computing "the same" numbers is how two surfaces start disagreeing in public - one counting
 * {@code INVITED} members as participants and the other not, on a screenshot somebody posts. The
 * query moved here rather than being copied, and the module that owns the MOTD keeps only the
 * cache and the rendering.
 *
 * <h2>It reads tables it does not own, and that is the trade</h2>
 * {@code hg_*} belongs to {@code hunger-games} and {@code smp_*} to {@code smp}
 * ({@code docs/architecture.md#schema-ownership}). The alternative was those plugins pushing their
 * state to the proxy and to the bot over plugin messages - two copies of a running game's state
 * plus a staleness problem per consumer. Counting rows costs one query and a dependency on their
 * schema, which is why {@link SnapshotDao} names the migrations it reads.
 *
 * <h2>Failures propagate</h2>
 * Same rule as {@code PhaseDirectory}: an unreachable database throws rather than returning
 * {@link NetworkSnapshot#EMPTY}, because "no game is running" and "I could not ask" have to be
 * distinguishable. Both callers keep their last good snapshot instead of blanking their surface,
 * and neither could implement that against a method that answers zeroes to both questions.
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
