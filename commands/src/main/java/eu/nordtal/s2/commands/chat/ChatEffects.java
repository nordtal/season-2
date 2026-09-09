package eu.nordtal.s2.commands.chat;

import eu.nordtal.s2.commands.CommandEffects;
import eu.nordtal.s2.commands.NordtalUser;

import java.util.UUID;

/**
 * Carrying one private line from one player to another, wherever on the network they are.
 *
 * <h2>Why the effect writes both lines and the command writes neither</h2>
 * A private message is two lines in two languages: the sender is told what they sent, in theirs, and
 * the recipient is told what arrived, in theirs. Only the process holding both connections can do
 * that, and the text has to be inserted as a <b>component</b> rather than as a substituted string -
 * a player who types {@code <red>} must not be able to colour somebody else's chat, and
 * {@code NordtalUser#reply} takes strings. So the effect composes and sends; the command decides who
 * it is for and says what to do when there is nobody there.
 *
 * <h2>Nothing here is recorded</h2>
 * Not in the log file, not in the admin channel, not in the database (Till, 2026-09-08). An
 * implementation that logged the text would be the only copy of it anywhere, which is a different
 * product from the one this is.
 */
public interface ChatEffects extends CommandEffects {

    /** What happened to a line somebody tried to send. */
    enum Outcome {

        /** Both sides have been told. */
        SENT,

        /**
         * The recipient is no longer connected.
         *
         * <p>Reachable even for {@code /msg}, where the name was resolved a moment earlier: the
         * resolution happens while the command is parsed and somebody can disconnect between that
         * and this. It is the ordinary answer for {@code /r}, where the partner was resolved
         * whenever the last message was.</p>
         */
        GONE,

        /** {@code /r} with nobody to reply to - nothing has been exchanged this session. */
        NO_PARTNER
    }

    /**
     * @param from who is sending, for their name, their language and their composition
     * @param to   the recipient, resolved from a name by the adapter
     * @param text exactly what was typed, never parsed as markup by anything
     */
    Outcome whisper(NordtalUser from, UUID to, String text);

    /**
     * The same, to whoever this session last exchanged a message with.
     *
     * <p>"This session" is literal: it is held in memory by the proxy and dies with the process, so
     * a restart costs everybody their reply partner and nothing else. It is deliberately not a
     * table - see {@code docs} for the rule that a private message leaves no record anywhere.</p>
     */
    Outcome replyToLast(NordtalUser from, String text);
}
