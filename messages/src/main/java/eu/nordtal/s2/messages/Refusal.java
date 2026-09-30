package eu.nordtal.s2.messages;

import java.util.Objects;

/**
 * A request refused: why, as a type a caller branches on, and what to tell whoever asked, as a message.
 * The same shape answers an inbox row, a command and an HTTP call; each renders the message in its own language.
 */
public record Refusal(RefusalReason reason, MessageRef message) {

    public Refusal {
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(message, "message");
    }
}
