package eu.nordtal.season.steward.texts;

import eu.nordtal.season.messages.MessageRef;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/** A refused request with a status, a reason as a message of the bundle, and a code for the page. */
public final class RequestRefused extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int status;
    private final transient MessageRef why;
    private final @Nullable String code;
    private final boolean retryable;
    private final @Nullable Duration retryAfter;

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
        this(status, why, code, retryable, null);
    }

    private RequestRefused(
            final int status,
            final MessageRef why,
            final @Nullable String code,
            final boolean retryable,
            final @Nullable Duration retryAfter) {
        super(why.key(), null, false, false);
        this.status = status;
        this.why = Objects.requireNonNull(why, "why");
        this.code = code;
        this.retryable = retryable;
        this.retryAfter = retryAfter;
    }

    /**
     * Refuses until {@code wait} has passed, which the answer's {@code Retry-After} header names.
     *
     * @param why words the wait, rounded up to whole seconds as the header carries it
     */
    public static RequestRefused retryAfter(
            final int status, final Duration wait, final Function<Duration, MessageRef> why) {
        final long seconds = Math.max(1, wait.toSeconds() + (wait.toNanosPart() > 0 ? 1 : 0));
        final Duration rounded = Duration.ofSeconds(seconds);
        return new RequestRefused(status, why.apply(rounded), null, false, rounded);
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

    /** Returns the wait in whole seconds, or null when the refusal names none. */
    public @Nullable Duration retryAfter() {
        return retryAfter;
    }
}
