package eu.nordtal.s2.messages.text;

/** A text that cannot be read: an unclosed brace, a plural without {@code other}, a broken tag argument. */
public final class MessageSyntaxException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    private final int position;

    MessageSyntaxException(final String message, final int position) {
        super(message + " (at character " + (position + 1) + ")");
        this.position = position;
    }

    /** Returns where in the text it failed, counted from 0. */
    public int position() {
        return position;
    }
}
