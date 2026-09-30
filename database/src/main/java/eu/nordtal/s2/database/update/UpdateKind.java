package eu.nordtal.s2.database.update;

import eu.nordtal.s2.database.inbox.WorkerRequest;
import java.util.List;

/**
 * What an {@link UpdateRequest} asks steward-worker to do: the kind of its request in the worker's inbox.
 * {@code UpdateKindTest} holds it against {@link WorkerRequest}'s kinds.
 */
public enum UpdateKind {

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

    /** Returns the request of this kind for the services, which the worker's inbox stores. */
    public WorkerRequest request(final List<String> services) {
        return switch (this) {
            case UPDATE -> new WorkerRequest.Update(services);
            case RESTART -> new WorkerRequest.Restart(services);
            case BACKUP -> new WorkerRequest.Backup(services);
            case DOWN -> new WorkerRequest.Down(services);
            case START -> new WorkerRequest.Start(services);
        };
    }

    /** Returns the kind of a request in the worker's inbox. */
    public static UpdateKind of(final WorkerRequest request) {
        return valueOf(WorkerRequest.TABLE.kindOf(request));
    }

    /** Returns whether this kind is half of the down and up pair, where a stopped service is the finished state. */
    public boolean isHold() {
        return this == DOWN || this == START;
    }
}
