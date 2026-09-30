package eu.nordtal.s2.database.network;

import javax.sql.DataSource;

/** One read of what the network is currently doing, shared by the MOTD and the bot's channel name. */
public interface SnapshotDirectory {

    /** Returns a directory over a connection pool the caller owns; there is nothing to close. */
    static SnapshotDirectory using(final DataSource dataSource) {
        return new JdbiSnapshotDirectory(dataSource);
    }

    /**
     * Returns the counts as of right now, never {@code null}.
     *
     * @throws RuntimeException if the database cannot be reached, so a caller keeps its last good snapshot
     */
    NetworkSnapshot snapshot();
}
