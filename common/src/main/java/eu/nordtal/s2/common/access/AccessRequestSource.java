package eu.nordtal.s2.common.access;

/**
 * Which surface asked for an access change.
 *
 * <p>For the audit trail and for nothing else: the bot carries out all four identically, and a
 * surface that could be treated specially here would be a surface with different rules, which is
 * the thing this table exists to remove.</p>
 */
public enum AccessRequestSource {

    /** A slash command in the guild. */
    DISCORD,

    /** Steward's web interface. */
    STEWARD,

    /** A command typed in Minecraft. */
    GAME,

    /** A server console, or a row somebody wrote by hand. */
    CONSOLE
}
