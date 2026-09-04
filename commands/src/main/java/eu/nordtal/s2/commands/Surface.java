package eu.nordtal.s2.commands;

/**
 * Where a command can be typed.
 *
 * <h2>A command declares its surfaces, and most do not have all three</h2>
 * {@code /navigate} opens an inventory and will never have a Discord half; {@code /settle}
 * autocompletes over open payment references and would be meaningless in chat. Declaring the set is
 * what lets each adapter ask "is this mine?" instead of every adapter carrying a list of exceptions
 * - which is the shape that goes stale the first time a command is added.
 *
 * <h2>{@link #CONSOLE} is separate from {@link #GAME} on purpose</h2>
 * They look alike - both arrive through the same Brigadier tree - and they are not the same
 * question. A command can be sensible in chat and impossible from the console ({@code /poi add}
 * reads your position), or the other way round. Keeping them apart is also what fixes the gap this
 * whole design started from: {@code /hg} cast its sender to a player in every handler, so the
 * console could run none of it, and the start of the event hung on exactly one client being able to
 * connect.
 */
public enum Surface {

    /** A chat command on a Paper server or on the proxy. */
    GAME,

    /** A slash command in the guild. */
    DISCORD,

    /** The server console, or the container's {@code mc} wrapper. */
    CONSOLE
}
