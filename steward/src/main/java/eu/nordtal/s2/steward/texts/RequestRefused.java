package eu.nordtal.s2.steward.texts;

import eu.nordtal.s2.messages.MessageRef;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A request Steward refuses: the status, why as a message of its bundle, and a code the page branches on.
 * Steward's error handlers render it with the admins' overrides, so no refusal a person reads is a literal.
 */
public final class RequestRefused extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int status;
    private final transient MessageRef why;
    private final @Nullable String code;
    private final boolean retryable;

    public RequestRefused(final int status, final MessageRef why) {
        this(status, why, null, false);
    }

    /**
     * Refuses with a code the page branches on.
     *
     * @param retryable whether the page may send the same request again once it has done what the code asks
     */
    public RequestRefused(
            final int status, final MessageRef why, final @Nullable String code, final boolean retryable) {
        super(why.key(), null, false, false);
        this.status = status;
        this.why = Objects.requireNonNull(why, "why");
        this.code = code;
        this.retryable = retryable;
    }

    public int status() {
        return status;
    }

    public MessageRef why() {
        return why;
    }

    public @Nullable String code() {
        return code;
    }

    public boolean retryable() {
        return retryable;
    }
}
