package eu.nordtal.s2.steward.worker.backup;

import org.jspecify.annotations.Nullable;

/**
 * The two halves of a backup: the volumes, tarred with the servers down, and the database, dumped while they run.
 *
 * @param volumes the tar of each volume, with the servers down
 * @param database the dump, or {@code null} when {@code backup.database-service} is empty, reported as not dumped
 */
public record Backups(Snapshots volumes, @Nullable DatabaseDump database) {

    /** The dump, or a failure that says the database was deliberately left out. */
    public SnapshotResult saveDatabase() {
        if (database == null) {
            return SnapshotResult.failed(
                    DatabaseDump.NAME,
                    java.time.Duration.ZERO,
                    "backup.database-service is empty, so no database dump was taken. Nothing is "
                            + "wrong with the database; nothing was saved of it either.");
        }
        return database.save();
    }
}
