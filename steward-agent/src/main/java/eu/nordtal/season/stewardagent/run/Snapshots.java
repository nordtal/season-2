package eu.nordtal.season.stewardagent.run;

import eu.nordtal.season.internalapi.agent.Retention;
import eu.nordtal.season.internalapi.agent.SnapshotResult;
import java.util.Collection;
import java.util.List;

/**
 * Saves the volumes and the database, synchronously, and sweeps old archives.
 *
 * The servers stay down while {@link #save} blocks, so every result reports a duration.
 */
public interface Snapshots {

    String DATABASE = "database";

    String OFFSITE = "offsite";

    /**
     * Dumps the database while everything runs, since pg_dump's snapshot needs nothing stopped.
     *
     * @return a failure when the deployment has no database to dump
     */
    SnapshotResult saveDatabase();

    /**
     * Saves one volume and returns when it is on disk or has failed.
     *
     * @param volume the docker volume name, for example {@code nordtal-s2_mc-smp}
     */
    SnapshotResult save(String volume);

    /**
     * Writes a sidecar mark for an archive whose servers stopped unverified.
     *
     * @param archive the path from {@link SnapshotResult#file()}
     * @param mark the run and the services whose end could not be read, which restore.sh shows before a restore
     * @return whether the mark was written. A failure is logged and keeps the backup.
     */
    boolean markUnverified(String archive, Unverified mark);

    /**
     * The mark beside an archive taken after a stop nobody could confirm.
     *
     * @param run the run that stopped the services and took the archive
     * @param services the services whose end could not be read
     */
    record Unverified(long run, List<String> services) {}

    /**
     * What a sweep removed.
     *
     * @param expired what the policy no longer keeps
     * @param overBudget what went afterwards because the archives took more disk than the budget
     */
    record Pruned(List<String> expired, List<String> overBudget) {

        public Pruned {
            expired = List.copyOf(expired);
            overBudget = List.copyOf(overBudget);
        }
    }

    /**
     * Whether a backup fits on the disk, asked before anything stops.
     *
     * @param expectedBytes the newest archive of each series, or the size of a new volume
     * @param usableBytes what the filesystem lets this process write now
     * @param reserveBytes what stays free for postgres and the servers
     */
    record Room(long expectedBytes, long usableBytes, long reserveBytes) {

        public boolean fits() {
            return usableBytes - expectedBytes >= reserveBytes;
        }
    }

    /**
     * Deletes what the policy no longer keeps, one series at a time, then what the disk budget cannot hold.
     *
     * @param policy the staggered schedule and the one-per-day collapse; see {@link Retention}
     * @param inBackup the volumes a backup saves now
     * @return what was removed, for the report
     */
    Pruned prune(Retention policy, Collection<String> inBackup);

    /**
     * Returns what a backup of these volumes and the database would write, against the free room on the disk.
     */
    Room room(Collection<String> volumes);

    /**
     * Returns the series of a finished archive: its volume, or {@link #DATABASE} for a dump.
     *
     * @return empty when there is no such archive
     */
    java.util.Optional<String> seriesOf(String archive);

    /**
     * Copies the newest finished archive of every series off this host and applies the policy there.
     *
     * @return empty when no offsite target is configured
     */
    default java.util.Optional<SnapshotResult> copyOffsite(final Retention policy) {
        return java.util.Optional.empty();
    }

    /**
     * What putting a volume archive back came to.
     *
     * @param touched whether the volume was changed, so that a failure may have left it incomplete
     */
    record Restored(SnapshotResult result, boolean touched) {}

    /** Puts a volume archive back into its volume. */
    Restored restore(String archive);

    /**
     * Replaces the database with a dump in one transaction, then applies the schema of this release.
     */
    SnapshotResult restoreDatabase(String dump);
}
