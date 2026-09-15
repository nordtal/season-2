package eu.nordtal.s2.steward.worker.backup;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The two halves of a backup, which are not alike.
 *
 * <h2>Why the database is not just another volume</h2>
 * Tarring a live {@code PGDATA} produces a torn copy that raises no error at backup time and is a
 * broken cluster at restore time. Stopping PostgreSQL for the length of a tar instead is a nightly
 * outage of every process in this stack. So the database is dumped, not snapshotted - and because a
 * dump is consistent as of the moment it starts, it can be taken <b>before anything is stopped</b>.
 *
 * <p>That ordering is the whole reason this pair exists as one object: the run takes the dump while
 * the servers are still up, and only then stops them for the volumes. The outage is the volumes'
 * length, not the sum.</p>
 *
 * @param volumes  the tar of each volume, with the servers down
 * @param database the dump, or {@code null} when {@code backup.database-service} is empty. Null is
 *                 reported as "not dumped" rather than quietly skipped: a backup that silently
 *                 omits the database is the one nobody notices until a restore
 */
public record Backups(@NotNull Snapshots volumes, @Nullable DatabaseDump database) {

    /** The dump, or a failure that says the database was deliberately left out. */
    public @NotNull SnapshotResult saveDatabase() {
        if (database == null) {
            return SnapshotResult.failed(DatabaseDump.NAME, java.time.Duration.ZERO,
                    "backup.database-service is empty, so no database dump was taken. Nothing is "
                    + "wrong with the database; nothing was saved of it either.");
        }
        return database.save();
    }
}
