package eu.nordtal.s2.steward.api;

/**
 * What became of asking the affected service to take a saved change, for a setting and a message alike.
 *
 * @param message the sentence the page shows, naming the service and what it said
 */
public record Reloading(Status status, String message) {

    /** {@code APPLIED} and {@code NO_ANSWER} both asked the service; {@code RESTART_REQUIRED} asked nothing. */
    public enum Status {
        APPLIED,
        NO_ANSWER,
        RESTART_REQUIRED
    }

    static Reloading applied(final String message) {
        return new Reloading(Status.APPLIED, message);
    }

    static Reloading noAnswer(final String message) {
        return new Reloading(Status.NO_ANSWER, message);
    }

    static Reloading restartRequired(final String message) {
        return new Reloading(Status.RESTART_REQUIRED, message);
    }
}
