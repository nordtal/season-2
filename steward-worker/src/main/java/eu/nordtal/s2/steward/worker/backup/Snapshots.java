package eu.nordtal.s2.steward.worker.backup;

import java.util.List;
import org.jetbrains.annotations.NotNull;

/**
 * Saving one volume, as a seam.
 *
 * <h2>Synchronous, unlike what it replaces</h2>
 * The panel's backup was two calls and a poll: ask for a snapshot, then ask again until it settled.
 * That shape went with it. A local {@code tar} either finishes or fails, in this thread, and the
 * caller knows which before the next line runs - so the servers are started again because the
 * saving is over, not because a status endpoint said something hopeful.
 *
 * <p>The cost is honest and belongs here: this blocks, and the four servers are down for as long as
 * it does. That is why {@link #save} answers with a duration, and why the report prints it.</p>
 */
public interface Snapshots {

    /**
     * Saves one volume and returns when it is on disk or has failed.
     *
     * @param volume the docker volume name, e.g. {@code nordtal-s2_mc-smp}
     */
    @NotNull
    SnapshotResult save(@NotNull String volume);

    /**
     * Writes the mark that says the servers behind this archive were stopped unverified.
     *
     * <p>A sidecar file rather than a different archive name, and that is the whole design
     * decision: {@code deploy/restore.sh} matches archives by name, the retention sweep groups
     * them by name, and a second naming scheme would have both of them silently skipping the file
     * that most needs looking at. A sidecar is visible to {@code --list}, invisible to everything
     * that only knows about {@code .tar.zst}, and swept away with the archive it belongs to.</p>
     *
     * @param archive the path {@link SnapshotResult#file()} came back with
     * @param why     what could not be confirmed, in a sentence somebody reads before restoring
     * @return the name of the mark that was written, or {@code null} if it could not be - which is
     *         logged and is not a reason to discard a backup that otherwise succeeded
     */
    @org.jetbrains.annotations.Nullable
    String markUnverified(@NotNull String archive, @NotNull String why);

    /**
     * Deletes whatever the policy no longer keeps, one series at a time.
     *
     * @param policy the staggered schedule and the one-per-day collapse; see {@link Retention}
     * @return what was removed, for the report - a retention that quietly deletes is one nobody
     *         notices has been deleting the wrong thing
     */
    @NotNull
    List<String> prune(@NotNull Retention policy);
}
