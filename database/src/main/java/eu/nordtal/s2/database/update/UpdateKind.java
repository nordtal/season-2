package eu.nordtal.s2.database.update;

/**
 * What an {@link UpdateRequest} asks steward-worker to do, stored in {@code update_request.kind}.
 *
 * An integration test holds this enum against the database {@code CHECK}.
 */
public enum UpdateKind {

    /** Resolves every source, compares against the volumes and writes the report; writes no file. */
    REPORT,

    /** Swapping jars into running servers; never submitted, kept so existing rows still map. */
    APPLY,

    /** Counts down, stops what changes, migrates, swaps and restarts; never stops the worker. */
    UPDATE,

    /** Counts down, stops, starts and waits, with nothing installed. */
    RESTART,

    /**
     * Counts down, stops, saves the volumes and starts again.
     *
     * Stopping first keeps a world copy from tearing; steward-worker's own clock submits the nightly one.
     */
    BACKUP,

    /**
     * Counts down, stops the named services and leaves them stopped.
     *
     * A row in {@code service_hold} keeps them down until {@link #START}; nothing expires it.
     */
    DOWN,

    /** Takes the hold off and starts the services again, with no countdown. */
    START;

    /** Returns whether this kind stops servers, which is what a confirmation is asked for. */
    public boolean stopsServers() {
        return this == UPDATE || this == RESTART || this == BACKUP || this == DOWN;
    }

    /** Returns whether this kind is half of the down and up pair, where a stopped service is the finished state. */
    public boolean isHold() {
        return this == DOWN || this == START;
    }
}
