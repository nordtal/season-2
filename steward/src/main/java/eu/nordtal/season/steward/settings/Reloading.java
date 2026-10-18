package eu.nordtal.season.steward.settings;

import eu.nordtal.season.messages.MessageRef;

/** @param message the sentence the page shows, naming the service */
public record Reloading(Status status, MessageRef message) {

    /** {@code APPLIED} reaches the service on its signal. {@code RESTART_REQUIRED} waits for the next start of the service. */
    public enum Status {
        APPLIED,
        RESTART_REQUIRED
    }

    public static Reloading applied(final MessageRef message) {
        return new Reloading(Status.APPLIED, message);
    }

    static Reloading restartRequired(final MessageRef message) {
        return new Reloading(Status.RESTART_REQUIRED, message);
    }
}
