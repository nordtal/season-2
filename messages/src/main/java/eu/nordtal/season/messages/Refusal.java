package eu.nordtal.season.messages;

import java.util.Objects;

/**
 * The refusal of a request, with a reason for the caller and a message for whoever asked.
 * An inbox row, a command and an HTTP call render the message in their own language.
 */
public record Refusal(RefusalReason reason, MessageRef message) {

    public Refusal {
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(message, "message");
    }
}
