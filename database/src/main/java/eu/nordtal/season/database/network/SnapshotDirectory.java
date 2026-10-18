package eu.nordtal.season.database.network;

import javax.sql.DataSource;

/** Reads what the network is doing, for the MOTD and the bot's channel name. */
public interface SnapshotDirectory {

    /** Returns a directory that borrows {@code dataSource}. */
    static SnapshotDirectory using(final DataSource dataSource) {
        return new JdbiSnapshotDirectory(dataSource);
    }

    /**
     * Returns the counts as of now.
     *
     * @throws RuntimeException if the database is unreachable, so a caller keeps its last snapshot
     */
    NetworkSnapshot snapshot();
}
