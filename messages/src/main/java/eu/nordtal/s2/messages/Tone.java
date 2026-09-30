package eu.nordtal.s2.messages;

/**
 * How a line should read at a glance: good news, bad news, or neither.
 * A command names a tone and each adapter paints it; Discord ignores it. It names no Adventure type, because the bot
 * loads it.
 */
public enum Tone {

    /** Nothing to flag, an ordinary reply. */
    NEUTRAL,

    /** It worked, it is current, it came back. */
    GOOD,

    /** It failed. */
    BAD,

    /** Not a failure, but not what was asked for either: stopped, too late, still waiting. */
    WARN,

    /** Supporting detail under a line that carries the news. */
    MUTED
}
