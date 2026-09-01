package eu.nordtal.s2.common.update;

/**
 * What an {@link UpdateRequest} asks the updater to do. Stored verbatim in
 * {@code update_request.kind}, which a database {@code CHECK} restricts to these three.
 *
 * <p>Three separate kinds rather than one command with flags, on purpose: a button that says
 * "apply" must not be able to become a restart because a column defaulted. Each is a different
 * amount of damage and each is asked for by name.</p>
 */
public enum UpdateKind {

    /** Resolve every source, compare against the volumes, write the report. Writes no file. */
    REPORT,

    /** Migrate, fetch and swap. Restarts nothing - the report is read before anything moves. */
    APPLY,

    /**
     * One Arcane redeploy of the whole project. Carries a {@code not_before} in the future, which
     * is the countdown players see.
     */
    RESTART
}
