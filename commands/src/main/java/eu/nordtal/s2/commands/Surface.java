package eu.nordtal.s2.commands;

/**
 * Where a command can be typed; each command declares its own set.
 *
 * {@link #CONSOLE} is separate from {@link #GAME}, since a command can make sense in one and not the other.
 */
public enum Surface {

    /** A chat command on a Paper server or on the proxy. */
    GAME,

    /** A slash command in the guild. */
    DISCORD,

    /** The server console, or the container's {@code mc} wrapper. */
    CONSOLE,

    /** The Steward web interface, whose rows always carry the asker's Discord id, unlike {@link #CONSOLE}. */
    WEB,

    /** Typed by no one: sent from one process to another as a row nobody waits on, written with {@code CONSOLE}. */
    SYSTEM
}
