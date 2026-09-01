package eu.nordtal.s2.networkcontrol.update;

/**
 * One thing to say to everybody on the network.
 *
 * @param kind    which of the three lines
 * @param seconds what is actually left, for {@link Kind#COUNTDOWN}; zero otherwise
 */
public record Announcement(Kind kind, long seconds) {

    public enum Kind {
        /** "The whole network restarts in {seconds} seconds." */
        COUNTDOWN,
        /** "Restarting now." */
        NOW,
        /** "The restart was called off." */
        CANCELLED
    }
}
