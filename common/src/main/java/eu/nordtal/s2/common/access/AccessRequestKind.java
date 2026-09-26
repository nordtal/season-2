package eu.nordtal.s2.common.access;

/**
 * What an {@link AccessRequest} asks the bot to do.
 *
 * Five verbs rather than one command with flags: each one is a different
 * thing to be allowed to do, and a grant that could turn into a revoke because a column defaulted
 * is a worse bug than any amount of repetition here. {@code V32__access_request.sql} holds the same
 * five in a {@code CHECK} constraint, which is where an unknown one is refused.
 */
public enum AccessRequestKind {

    /** Add days of access to {@link AccessRequest#subject()}, apply the role, and tell them. */
    GRANT,

    /** Take every running grant away, remove the role, and tell them. */
    REVOKE,

    /** Book a payment by hand. The subject is a payment reference, not an account. */
    SETTLE,

    /** Break the link between a Discord account and a Minecraft one. */
    UNLINK,

    /** Write somebody's total play time. The argument is seconds, which is what the column holds. */
    SET_PLAYTIME,

    /**
     * Re-read the bot's message bundles.
     *
     * The odd one out, and deliberately here rather than on a reload mechanism of its own: this
     * inbox is "do the thing only you can do", and re-reading a bundle held in the bot's own memory
     * is exactly that. {@code ConfigApi}'s console-command map stays what reloads a Minecraft
     * service; the bot has no console.
     *
     * The subject is the bundle whose override was written. The bot re-reads all of them either
     * way - they are layered - so it is recorded rather than acted on.
     */
    RELOAD_MESSAGES
}
