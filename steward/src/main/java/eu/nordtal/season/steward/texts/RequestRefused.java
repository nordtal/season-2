package eu.nordtal.season.steward.texts;

import eu.nordtal.season.messages.MessageRef;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** A refused request with a status, a reason as a message of the bundle, and a code for the page. */
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
     * Refuses with a code for the page.
     *
     * @param retryable whether the page may repeat the request after it has done what the code says
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
