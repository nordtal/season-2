package eu.nordtal.s2.steward.worker.docker;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Anything the daemon answered that the caller cannot use.
 *
 * <p>It carries the status and the body because Docker's error bodies are short and say what is
 * actually wrong ({@code {"message":"No such container: smp"}}), and a stack trace that has thrown
 * that away leaves the reader guessing at a thing the daemon already explained.</p>
 */
public class DockerException extends RuntimeException {

    private final int status;
    private final @Nullable String body;

    public DockerException(final @NotNull String message) {
        this(message, 0, null, null);
    }

    public DockerException(final @NotNull String message, final @Nullable Throwable cause) {
        this(message, 0, null, cause);
    }

    public DockerException(
            final @NotNull String message,
            final int status,
            final @Nullable String body,
            final @Nullable Throwable cause) {
        super(body == null || body.isBlank() ? message : message + ": " + body.strip(), cause);
        this.status = status;
        this.body = body;
    }

    /** The HTTP status, or 0 when the request never got an answer at all. */
    public int status() {
        return status;
    }

    public @Nullable String body() {
        return body;
    }
}
