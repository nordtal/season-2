package eu.nordtal.s2.database.update;

import eu.nordtal.s2.database.inbox.StewardRequest;
import java.util.List;

/**
 * What an {@link UpdateRequest} asks steward to do: the kind of its request in the run inbox.
 * {@code UpdateKindTest} holds it against {@link StewardRequest}'s kinds.
 */
public enum UpdateKind {

    /** Counts down, stops what changes, migrates, swaps and restarts; never stops steward. */
    UPDATE,

    /** Counts down, stops, starts and waits, with nothing installed. */
    RESTART,

    /**
     * Counts down, stops, saves the volumes and starts again.
     *
     * Stopping first keeps a world copy from tearing; steward's own clock submits the nightly one.
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

    /** Returns the request of this kind for the services, which the run inbox stores. */
    public StewardRequest request(final List<String> services) {
        return switch (this) {
            case UPDATE -> new StewardRequest.Update(services);
            case RESTART -> new StewardRequest.Restart(services);
            case BACKUP -> new StewardRequest.Backup(services);
            case DOWN -> new StewardRequest.Down(services);
            case START -> new StewardRequest.Start(services);
        };
    }

    /** Returns the kind of a request in the run inbox. */
    public static UpdateKind of(final StewardRequest request) {
        return valueOf(StewardRequest.TABLE.kindOf(request));
    }

    /** Returns whether this kind is half of the down and up pair, where a stopped service is the finished state. */
    public boolean isHold() {
        return this == DOWN || this == START;
    }
}
