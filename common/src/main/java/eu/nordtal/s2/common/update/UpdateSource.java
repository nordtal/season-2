package eu.nordtal.s2.common.update;

/**
 * Which surface an {@link UpdateRequest} came from. Stored verbatim in
 * {@code update_request.source}, which a database {@code CHECK} restricts to these three.
 *
 * <p>The updater treats all three identically - a restart asked for from a chat line is the same
 * restart. This column exists for the person reading the table afterwards.</p>
 */
public enum UpdateSource {

    /** {@code /update} in the admin channel. */
    DISCORD,

    /** {@code /smp update} on a backend server. */
    GAME,

    /** {@code updater apply} run by hand on the host. */
    CONSOLE
}
