package eu.nordtal.s2.database.update;

/** Which surface an {@link UpdateRequest} came from, stored in {@code update_request.source}. */
public enum UpdateSource {

    /** {@code /update} in the admin channel. */
    DISCORD,

    /** {@code /smp update} on a backend server. */
    GAME,

    /** {@code updater apply} run by hand on the host. */
    CONSOLE
}
