package eu.nordtal.season.messages.text;

import eu.nordtal.season.messages.MessageRef;

/**
 * Thrown for a text that cannot be read, such as an unclosed brace, a plural without {@code other}
 * or a broken tag argument.
 */
public final class MessageSyntaxException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    private final transient MessageRef reason;
    private final int position;

    MessageSyntaxException(final MessageRef reason, final int position) {
        super(reason.key());
        this.reason = reason;
        this.position = position;
    }

    public MessageRef reason() {
        return reason;
    }

    public int position() {
        return position;
    }

    @Override
    public String getMessage() {
        return MessageCheck.english(reason);
    }
}
