package eu.nordtal.season.common.http;

import java.io.IOException;
import java.net.URI;

/** An answer with a status other than 2xx. {@link IOException} covers a request that got no answer. */
public final class HttpFailure extends IOException {

    private static final int BODY_SHOWN = 300;

    private final URI uri;
    private final int status;
    private final String body;

    public HttpFailure(final URI uri, final int status, final String body) {
        this(uri, status, body, "");
    }

    private HttpFailure(final URI uri, final int status, final String body, final String hint) {
        super(explain(uri, status, body, hint));
        this.uri = uri;
        this.status = status;
        this.body = body;
    }

    /** Returns the failure with {@code hint} after the status. */
    public HttpFailure withHint(final String hint) {
        return new HttpFailure(uri, status, body, hint);
    }

    public URI uri() {
        return uri;
    }

    public int status() {
        return status;
    }

    public String body() {
        return body;
    }

    private static String explain(final URI uri, final int status, final String body, final String hint) {
        final StringBuilder message =
                new StringBuilder("HTTP ").append(status).append(" from ").append(uri);
        message.append(hint);
        final String trimmed = body.strip();
        if (!trimmed.isEmpty()) {
            message.append(" Body: ")
                    .append(trimmed.length() > BODY_SHOWN ? trimmed.substring(0, BODY_SHOWN) + "..." : trimmed);
        }
        return message.toString();
    }
}
