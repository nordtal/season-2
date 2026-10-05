package eu.nordtal.season.messages.text;

import eu.nordtal.season.messages.MessageRef;

/** A text that cannot be read: an unclosed brace, a plural without {@code other}, a broken tag argument. */
public final class MessageSyntaxException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    private final transient MessageRef reason;
    private final int position;

    MessageSyntaxException(final MessageRef reason, final int position) {
        super(reason.key());
        this.reason = reason;
        this.position = position;
    }

    /** Returns why, a message of the {@code check} bundle that names the character too. */
    public MessageRef reason() {
        return reason;
    }

    /** Returns where in the text it failed, counted from 0. */
    public int position() {
        return position;
    }

    /** Returns why in English, as the packaged texts say it. */
    @Override
    public String getMessage() {
        return MessageCheck.english(reason);
    }
}
