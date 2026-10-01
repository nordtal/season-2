package eu.nordtal.s2.steward.backup;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Saves one volume, synchronously.
 *
 * The servers stay down while it blocks, which is why {@link #save} reports a duration.
 */
public interface Snapshots {

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
     * Deletes whatever the policy no longer keeps, one series at a time.
     *
     * @param policy the staggered schedule and the one-per-day collapse; see {@link Retention}
     * @return what was removed, for the report
     */
    List<String> prune(Retention policy);
}
