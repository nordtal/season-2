package eu.nordtal.s2.database.access;

/** Which surface asked for an access change, recorded for the audit trail only. */
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
