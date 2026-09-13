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
    CONSOLE,

    /**
     * The Steward web interface.
     *
     * <p>Added 2026-09-13 (concept §10b). It is a real surface and not a convenience: a command
     * asked for here is a {@code command_request} row like any other, so the interface needs
     * neither RCON nor tmux for the five admin commands that stayed in the game, and none of the
     * command logic is rebuilt anywhere.</p>
     *
     * <p><b>A WEB row always carries the asker's Discord id</b>, pinned by a CHECK in V18 the same
     * way {@link #DISCORD} is. That is the whole reason this value exists rather than reusing
     * {@link #CONSOLE}: V11 pins a CONSOLE row to having no identity at all, so an admin command
     * sent from the interface as CONSOLE would be anonymous - and "who did this" is precisely what
     * the journal exists to answer.</p>
     */
    WEB,

    /**
     * Typed by no one: a command one process sends to another as a {@code command_request} row,
     * with nobody waiting for the answer beyond the row itself. No adapter registers a SYSTEM
     * command anywhere a person could type it, and the catalogue does not ask it for a description
     * a person would read. Added 2026-09-06 for {@code announce}, the SMP's line into the Discord
     * announcement channels - the transport was there since V11, this is the surface it lacked.
     *
     * <p><b>SYSTEM is not a value of {@code command_request.source}</b>, and {@link #WEB} is. The
     * distinction is easy to miss: this one describes where a command may be <em>registered</em>,
     * and the row {@code announce} travels on is written with {@code CONSOLE} - correctly, because
     * it genuinely has no human identity behind it. So V18 adds WEB to the source CHECK and does
     * not add SYSTEM, which nothing has ever written.</p>
     */
    SYSTEM
}
