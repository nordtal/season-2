package eu.nordtal.season.stewardagent.source;

import eu.nordtal.season.common.http.HttpFailure;
import eu.nordtal.season.common.http.WebClient;
import eu.nordtal.season.common.time.Backoff;
import eu.nordtal.season.common.time.Waiting;
import java.time.Duration;

/** The client for GitHub, Modrinth and the Fill API, with explanations of their usual failures. */
public final class SourceHttp {

    public static final String USER_AGENT = "nordtal-season-2/steward (+https://github.com/nordtal/season-2)";

    /** A failed connection is tried again for half a minute, so a short network loss does not fail a run. */
    private static final int ATTEMPTS = 6;

    private SourceHttp() {}

    /**
     * Returns the client for the three sources, following their redirects.
     *
     * @param token a GitHub token sent on every request, blank for none
     */
    public static WebClient client(final Duration timeout, final String token, final Waiting waiting) {
        return WebClient.create(timeout)
                .followingRedirects()
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .bearer(token)
                .retrying(ATTEMPTS, new Backoff(Duration.ofSeconds(1), Duration.ofSeconds(16)), waiting);
    }

    /** Returns the resolver's seam over {@code web}, explaining a 404 and a rate limit in the failure. */
    public static Http over(final WebClient web) {
        return uri -> {
            try {
                return web.text(uri);
            } catch (final HttpFailure failure) {
                throw explained(failure);
            }
        };
    }

    private static HttpFailure explained(final HttpFailure failure) {
        return switch (failure.status()) {
            case 404 ->
                failure.withHint(" - the resource does not exist. For a GitHub release this"
                        + " usually means the tag is not published (a draft is invisible to the API),"
                        + " and for Modrinth it means the project id is wrong.");
            case 403, 429 ->
                failure.withHint(" - rate limited. GitHub allows 60 unauthenticated"
                        + " requests per hour per IP; set github-token in the steward group if this host"
                        + " shares its address.");
            default -> failure;
        };
    }
}
