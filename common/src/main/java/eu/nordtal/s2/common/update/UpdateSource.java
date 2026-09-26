package eu.nordtal.s2.common.update;

/**
 * Which surface an {@link UpdateRequest} came from, stored in {@code update_request.source}.
 *
 * steward-worker treats all of them alike; the column is for the person reading the table.
 */
public enum UpdateSource {

    /** {@code /update} in the admin channel. */
    DISCORD,

    /** {@code /smp update} on a backend server. */
    GAME,

    /** {@code updater apply} run by hand on the host. */
    CONSOLE
}
