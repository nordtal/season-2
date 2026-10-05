package eu.nordtal.season.stewardagent.docker;

import org.jspecify.annotations.Nullable;

/** Anything the daemon answered that the caller cannot use, carrying its status and short error body. */
public class DockerException extends RuntimeException {

    private final int status;
    private final @Nullable String body;

    public DockerException(final String message) {
        this(message, 0, null, null);
    }

    public DockerException(final String message, final @Nullable Throwable cause) {
        this(message, 0, null, cause);
    }

    public DockerException(
            final String message, final int status, final @Nullable String body, final @Nullable Throwable cause) {
        super(body == null || body.isBlank() ? message : message + ": " + body.strip(), cause);
        this.status = status;
        this.body = body;
    }

    /** The HTTP status, or 0 when the request never got an answer. */
    public int status() {
        return status;
    }

    public @Nullable String body() {
        return body;
    }
}
