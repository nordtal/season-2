package eu.nordtal.s2.commands;

/**
 * Where a command can be typed.
 *
 * A command declares its surfaces, and most do not have all three: {@code /navigate} opens an
 * inventory and will never have a Discord half; {@code /settle} autocompletes over open payment
 * references and would be meaningless in chat. Declaring the set is what lets each adapter ask "is
 * this mine?" instead of every adapter carrying a list of exceptions - the shape that goes stale
 * the first time a command is added.
 *
 * {@link #CONSOLE} is separate from {@link #GAME} on purpose. They look alike - both arrive through
 * the same Brigadier tree - and they are not the same question. A command can be sensible in chat
 * and impossible from the console ({@code /poi add} reads your position), or the other way round.
 */
public enum Surface {

    /** A chat command on a Paper server or on the proxy. */
    GAME,

    /** A slash command in the guild. */
    DISCORD,

    /** The server console, or the container's {@code mc} wrapper. */
    CONSOLE,

    /**
     * The Steward web interface.
     *
     * A real surface and not a convenience: a command
     * asked for here is a {@code command_request} row like any other, so the interface needs
     * neither RCON nor tmux for the five admin commands that stayed in the game, and none of the
     * command logic is rebuilt anywhere.
     *
     * <b>A WEB row always carries the asker's Discord id</b>, pinned by a CHECK in V18 the same
     * way {@link #DISCORD} is. That is the whole reason this value exists rather than reusing
     * {@link #CONSOLE}: V11 pins a CONSOLE row to having no identity at all, so an admin command
     * sent from the interface as CONSOLE would be anonymous - and "who did this" is precisely what
     * the journal exists to answer.
     */
    WEB,

    /**
     * Typed by no one: sent from one process to another as a {@code command_request} row nobody waits on.
     *
     * No adapter registers a SYSTEM command anywhere a person could type it, and the catalogue does
     * not ask it for a description a person would read. It exists for {@code announce}, the SMP's
     * line into the Discord announcement channels.
     *
     * <b>SYSTEM is not a value of {@code command_request.source}</b>, and {@link #WEB} is. The
     * distinction is easy to miss: this one describes where a command may be <em>registered</em>,
     * and the row {@code announce} travels on is written with {@code CONSOLE} - correctly, because
     * it genuinely has no human identity behind it.
     */
    SYSTEM
}
