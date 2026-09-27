package eu.nordtal.s2.proxy.update;

/**
 * One thing to say to everybody on the network.
 *
 * @param seconds what is left, for {@link Kind#COUNTDOWN} and {@link Kind#TICK}; zero otherwise
 */
public record Announcement(Kind kind, long seconds) {

    public enum Kind {
        /** Chat: "The whole network restarts in {seconds} seconds." */
        COUNTDOWN,

        /** A subtitle carrying the number alone, once a second for the last ten, for players with chat closed. */
        TICK,

        /** Chat and a subtitle: "Restarting now." */
        NOW,

        /** Chat: "The restart was called off." */
        CANCELLED,

        /** Chat: "It was asked for and it is not happening", so a failure does not read as a withdrawal. */
        FAILED
    }
}
