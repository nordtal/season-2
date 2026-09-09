package eu.nordtal.s2.networkcontrol.update;

/**
 * One thing to say to everybody on the network.
 *
 * @param kind    which line
 * @param seconds what is actually left, for {@link Kind#COUNTDOWN} and {@link Kind#TICK}; zero
 *                otherwise
 */
public record Announcement(Kind kind, long seconds) {

    public enum Kind {
        /** Chat: "The whole network restarts in {seconds} seconds." */
        COUNTDOWN,

        /**
         * A subtitle carrying the number alone, once a second for the last ten.
         *
         * <p>New 2026-09-08. Chat is where a warning is <em>read</em> and the last ten seconds are
         * not long enough to read anything - they are long enough to look up. A player mining with
         * the chat box closed saw the whole countdown and nothing else; the subtitle is the half
         * that reaches them without asking them to be looking at the right corner.</p>
         */
        TICK,

        /** Chat and a subtitle: "Restarting now." */
        NOW,

        /** Chat: "The restart was called off." */
        CANCELLED,

        /**
         * Chat: "It was asked for and it is not happening."
         *
         * <p>Added 2026-09-03 with finding 39. Before it, a restart that reached zero and then
         * failed - an unreachable Arcane, a refused token - looked to a player exactly like one
         * somebody had withdrawn, because those were the only two lines there were.
         */
        FAILED
    }
}
