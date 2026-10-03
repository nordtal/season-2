package eu.nordtal.s2.steward.api;

/**
 * When a saved change takes effect: at once on the service's signal, or at its next start.
 * It answers a saved setting and a saved message alike.
 *
 * @param message the sentence the page shows, naming the service
 */
public record Reloading(Status status, String message) {

    /** {@code APPLIED} reaches the service on its signal; {@code RESTART_REQUIRED} waits for its next start. */
    public enum Status {
        APPLIED,
        RESTART_REQUIRED
    }

    static Reloading applied(final String message) {
        return new Reloading(Status.APPLIED, message);
    }

    static Reloading restartRequired(final String message) {
        return new Reloading(Status.RESTART_REQUIRED, message);
    }
}
