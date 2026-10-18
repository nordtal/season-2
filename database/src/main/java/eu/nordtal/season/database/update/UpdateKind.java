package eu.nordtal.season.database.update;

import eu.nordtal.season.database.inbox.StewardRequest;
import java.util.List;

/**
 * What an {@link UpdateRequest} asks steward to do: the kind of its request in the run inbox.
 * {@code UpdateKindTest} holds it against the kinds of {@link StewardRequest}.
 */
public enum UpdateKind {

    /** Counts down, stops what changes, migrates, swaps and restarts. It never stops steward. */
    UPDATE,

    /** Counts down, stops, starts and waits, with nothing installed. */
    RESTART,

    /**
     * Counts down, stops, saves the volumes and starts again.
     * Stopping first keeps a world copy from tearing. Steward's own clock submits the nightly backup.
     */
    BACKUP,

    /**
     * Counts down, stops the named services and leaves them stopped.
     * A row in {@code service_hold} keeps them down until {@link #START}. Nothing expires it.
     */
    DOWN,

    /** Removes the hold and starts the services again, without a countdown. */
    START,

    /** Takes a fresh backup, counts down, stops what the archive belongs to, puts it back and starts again. */
    RESTORE,

    /** Counts down and recreates the containers of the services from the images on this host. */
    RECREATE,

    /** Counts down, pulls the images of the services and recreates their containers. */
    DEPLOY,

    /** Counts down, stops one server, removes one jar from its plugins folder and starts it again. */
    REMOVE_PLUGIN,

    /**
     * Puts what has nothing installed into the volumes when steward-agent starts, before any server runs.
     * Nobody asks for it: steward-agent writes the row when done, already settled.
     */
    INSTALL;

    /** Returns whether this kind stops servers, which a confirmation is asked for. */
    public boolean stopsServers() {
        return this != START && this != INSTALL;
    }

    /**
     * Returns the request of this kind for the services.
     *
     * @throws IllegalArgumentException for a kind that names more than services (a restore, a plugin removal)
     *     and for the start-up install
     */
    public StewardRequest request(final List<String> services) {
        return switch (this) {
            case UPDATE -> new StewardRequest.Update(services);
            case RESTART -> new StewardRequest.Restart(services);
            case BACKUP -> new StewardRequest.Backup(services);
            case DOWN -> new StewardRequest.Down(services);
            case START -> new StewardRequest.Start(services);
            case RECREATE -> new StewardRequest.Recreate(services);
            case DEPLOY -> new StewardRequest.Deploy(services);
            case RESTORE, REMOVE_PLUGIN ->
                throw new IllegalArgumentException(this + " names more than its services; submit the request itself");
            case INSTALL ->
                throw new IllegalArgumentException("steward-agent installs on its start; nobody asks for it");
        };
    }

    public static UpdateKind of(final StewardRequest request) {
        return valueOf(StewardRequest.TABLE.kindOf(request));
    }

    /** Returns whether this kind is half of the down and up pair, where a stopped service is the end state. */
    public boolean isHold() {
        return this == DOWN || this == START;
    }
}
