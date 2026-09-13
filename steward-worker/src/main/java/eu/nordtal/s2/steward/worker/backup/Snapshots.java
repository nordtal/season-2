package eu.nordtal.s2.steward.worker.backup;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Saving one volume, as a seam.
 *
 * <h2>Synchronous, unlike what it replaces</h2>
 * Arcane's backup was two calls and a poll: ask for a snapshot, then ask again until it settled.
 * That shape is gone with it. A local {@code tar} either finishes or fails, in this thread, and the
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
    @NotNull SnapshotResult save(@NotNull String volume);

    /**
     * Deletes the oldest archives until {@code keep} of each kind remain.
     *
     * @return what was removed, for the report - a retention that quietly deletes is one nobody
     *         notices has been deleting the wrong thing
     */
    @NotNull List<String> prune(int keep);
}
