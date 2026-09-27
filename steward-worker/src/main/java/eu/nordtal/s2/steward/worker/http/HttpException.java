package eu.nordtal.s2.steward.worker.http;

import java.io.IOException;
import java.net.URI;
import lombok.Getter;

/** A server answered with something other than success, unlike an {@link IOException}, where none answered. */
@Getter
public class HttpException extends IOException {

    private final int status;
    private final URI uri;

    public HttpException(final URI uri, final int status, final String body) {
        super(explain(uri, status, body));
        this.uri = uri;
        this.status = status;
    }

    /** The HTTP status the server answered with. */
    public int status() {
        return status;
    }

    private static String explain(final URI uri, final int status, final String body) {
        final StringBuilder message =
                new StringBuilder("HTTP ").append(status).append(" from ").append(uri);
        switch (status) {
            case 404 ->
                message.append(" - the resource does not exist. For a GitHub release this"
                        + " usually means the tag is not published (a draft is invisible to the API),"
                        + " and for Modrinth it means the project id is wrong.");
            case 403, 429 ->
                message.append(" - rate limited. GitHub allows 60 unauthenticated"
                        + " requests per hour per IP; set github-token in steward.yml if this host"
                        + " shares its address.");
            default -> {}
        }
        // Trimmed hard: an error body is one sentence in a page of JSON, and this ends up in a Discord embed.
        final String trimmed = body == null ? "" : body.strip();
        if (!trimmed.isEmpty()) {
            message.append(" Body: ").append(trimmed.length() > 300 ? trimmed.substring(0, 300) + "..." : trimmed);
        }
        return message.toString();
    }
}
