package eu.nordtal.s2.common.http;

import java.io.IOException;
import java.net.URI;

/** An answer that was not a 2xx, unlike a plain {@link IOException}, where nothing answered. */
public final class HttpFailure extends IOException {

    /** How much of a body the message keeps: an error is one sentence in a page of JSON, and it ends up in Discord. */
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

    /** Returns the same failure with {@code hint} after the status, saying what it usually means for this caller. */
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
