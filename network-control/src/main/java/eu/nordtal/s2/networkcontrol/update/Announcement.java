package eu.nordtal.s2.networkcontrol.update;

/**
 * One thing to say to everybody on the network.
 *
 * @param kind    which of the four lines
 * @param seconds what is actually left, for {@link Kind#COUNTDOWN}; zero otherwise
 */
public record Announcement(Kind kind, long seconds) {

    public enum Kind {
        /** "The whole network restarts in {seconds} seconds." */
        COUNTDOWN,
        /** "Restarting now." */
        NOW,
        /** "The restart was called off." */
        CANCELLED,
        /**
         * "It was asked for and it is not happening."
         *
         * <p>Added 2026-09-03 with finding 39. Before it, a restart that reached zero and then
         * failed - an unreachable Arcane, a refused token - looked to a player exactly like one
         * somebody had withdrawn, because those were the only two lines there were.
         */
        FAILED
    }
}
