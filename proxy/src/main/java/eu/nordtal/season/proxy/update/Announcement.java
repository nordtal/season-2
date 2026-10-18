package eu.nordtal.season.proxy.update;

/**
 * A message to every player on the network.
 *
 * @param seconds the remaining seconds for {@link Kind#COUNTDOWN} and {@link Kind#TICK}, otherwise zero
 */
public record Announcement(Kind kind, long seconds) {

    public enum Kind {
        /** A chat message with the remaining seconds. */
        COUNTDOWN,

        /** A subtitle with the remaining seconds. */
        TICK,

        /** A chat message and a subtitle that the restart starts now. */
        NOW,

        /** A chat message that the restart was cancelled. */
        CANCELLED,

        /** A chat message that the restart failed. */
        FAILED
    }
}
