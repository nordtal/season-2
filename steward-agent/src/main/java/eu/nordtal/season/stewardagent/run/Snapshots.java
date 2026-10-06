package eu.nordtal.season.stewardagent.run;

import eu.nordtal.season.internalapi.agent.Retention;
import eu.nordtal.season.internalapi.agent.SnapshotResult;
import java.util.Collection;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Saves the volumes and the database, synchronously, and sweeps old archives.
 *
 * The servers stay down while {@link #save} blocks, which is why every result reports a duration.
 */
public interface Snapshots {

    /** The name the database dump goes by, in a result and on a run's report line. */
    String DATABASE = "database";

    /** The name the copy off this host goes by, in a result and on a run's report line. */
    String OFFSITE = "offsite";

    /**
     * Dumps the database while everything runs, since pg_dump's snapshot needs nothing stopped.
     *
     * @return a failure naming why when the deployment dumps no database at all
     */
    SnapshotResult saveDatabase();

    /**
     * Saves one volume and returns when it is on disk or has failed.
     *
     * @param volume the docker volume name, for example {@code nordtal-s2_mc-smp}
     */
    SnapshotResult save(String volume);

    /**
     * Writes a sidecar mark saying the servers behind this archive were stopped unverified.
     *
     * @param archive the path {@link SnapshotResult#file()} came back with
     * @param why what could not be confirmed, in a sentence somebody reads before restoring
     * @return the name of the mark, or {@code null} if it could not be written, which is logged and keeps the backup
     */
    @Nullable
    String markUnverified(String archive, String why);

    /**
     * What a sweep removed.
     *
     * @param expired what the policy no longer keeps
     * @param overBudget what went afterwards because the archives took more of the disk than the budget allows
     */
    record Pruned(List<String> expired, List<String> overBudget) {

        public Pruned {
            expired = List.copyOf(expired);
            overBudget = List.copyOf(overBudget);
        }
    }

    /**
     * Whether a backup fits on the disk, asked before anything stops for it.
     *
     * @param expectedBytes what it is expected to write: each series' newest archive, or a new volume's size
     * @param usableBytes what the filesystem lets this process write now
     * @param reserveBytes what has to stay free for postgres and the servers
     */
    record Room(long expectedBytes, long usableBytes, long reserveBytes) {

        public boolean fits() {
            return usableBytes - expectedBytes >= reserveBytes;
        }
    }

    /**
     * Deletes whatever the policy no longer keeps, one series at a time, then whatever the disk budget cannot hold.
     * A volume outside {@code inBackup} is counted by the calendar, so its archives age out.
     *
     * @param policy the staggered schedule and the one-per-day collapse; see {@link Retention}
     * @param inBackup the volumes a backup saves now
     * @return what was removed, for the report
     */
    Pruned prune(Retention policy, Collection<String> inBackup);

    /** What a backup of these volumes and the database would write, against the room the disk has for it. */
    Room room(Collection<String> volumes);

    /**
     * The series a finished archive in the backups belongs to: its volume, or {@link #DATABASE} for a dump.
     *
     * @return empty when there is no such finished archive
     */
    java.util.Optional<String> seriesOf(String archive);

    /**
     * Copies the newest finished archive of every series off this host and applies the policy there too.
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

    /** Puts a volume archive back into its volume, which every server on it has stopped for. */
    Restored restore(String archive);

    /** Replaces the database with a dump in one transaction, then brings its schema up to this release's. */
    SnapshotResult restoreDatabase(String dump);
}
