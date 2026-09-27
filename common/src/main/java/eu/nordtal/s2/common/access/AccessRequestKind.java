package eu.nordtal.s2.common.access;

/**
 * What an {@link AccessRequest} asks the bot to do.
 * {@code V32__access_request.sql} holds the same five in a {@code CHECK} constraint.
 */
public enum AccessRequestKind {

    /** Adds days of access to {@link AccessRequest#subject()}, applies the role and tells them. */
    GRANT,

    /** Takes every running grant away, removes the role and tells them. */
    REVOKE,

    /** Books a payment by hand; the subject is a payment reference, not an account. */
    SETTLE,

    /** Breaks the link between a Discord account and a Minecraft one. */
    UNLINK,

    /** Writes somebody's total play time, in seconds. */
    SET_PLAYTIME,

    /**
     * Re-reads the bot's message bundles.
     * The subject names the bundle whose override was written, but all of them are re-read.
     */
    RELOAD_MESSAGES
}
