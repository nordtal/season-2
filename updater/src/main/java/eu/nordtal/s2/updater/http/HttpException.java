package eu.nordtal.s2.updater.http;

import lombok.Getter;

import java.io.IOException;
import java.net.URI;

/**
 * A request that reached a server and came back as something other than success.
 * <p>
 * Separate from a plain {@link IOException} because the two mean opposite things to whoever reads
 * the report: an {@code IOException} is "the network was not there and it may be there in a
 * minute", while this is "the server answered, and the answer was no". A 404 on
 * {@code /releases/latest} is not an outage - it is a repository with no published release, which
 * is a thing a person has to go and do.
 * </p>
 */
@Getter
public class HttpException extends IOException {

    private final int status;
    private final URI uri;

    public HttpException(final URI uri, final int status, final String body) {
        super(explain(uri, status, body));
        this.uri = uri;
        this.status = status;
    }

    private static String explain(final URI uri, final int status, final String body) {
        final StringBuilder message = new StringBuilder("HTTP ").append(status).append(" from ").append(uri);
        switch (status) {
            case 404 -> message.append(" - the resource does not exist. For a GitHub release this"
                    + " usually means the tag is not published (a draft is invisible to the API),"
                    + " and for Modrinth it means the project id is wrong.");
            case 403, 429 -> message.append(" - rate limited. GitHub allows 60 unauthenticated"
                    + " requests per hour per IP; set github-token in updater.yml if this host"
                    + " shares its address.");
            default -> { }
        }
        // Trimmed hard: an API error body is one useful sentence wrapped in a page of JSON, and
        // this string ends up in a Discord embed with a 4096 character budget.
        final String trimmed = body == null ? "" : body.strip();
        if (!trimmed.isEmpty()) {
            message.append(" Body: ")
                    .append(trimmed.length() > 300 ? trimmed.substring(0, 300) + "..." : trimmed);
        }
        return message.toString();
    }
}
