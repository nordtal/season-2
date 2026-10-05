package eu.nordtal.season.stewardagent.source;

import eu.nordtal.season.common.http.HttpFailure;
import eu.nordtal.season.common.http.WebClient;
import eu.nordtal.season.common.time.Backoff;
import eu.nordtal.season.common.time.Waiting;
import java.time.Duration;

/** The kernel's client as GitHub, Modrinth and the Fill API see it, with what their failures usually mean. */
public final class SourceHttp {

    /** How this process identifies itself to GitHub, Modrinth and the Fill API. */
    public static final String USER_AGENT = "nordtal-season-2/steward (+https://github.com/nordtal/season-2)";

    /** A connection that failed is tried again twice, since one lost packet should not fail a whole resolve. */
    private static final int ATTEMPTS = 3;

    private SourceHttp() {}

    /**
     * Returns the client for the three sources, following their redirects.
     *
     * @param token an optional GitHub token, blank for none, sent on every request
     */
    public static WebClient client(final Duration timeout, final String token, final Waiting waiting) {
        return WebClient.create(timeout)
                .followingRedirects()
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .bearer(token)
                .retrying(ATTEMPTS, new Backoff(Duration.ofSeconds(1), Duration.ofSeconds(4)), waiting);
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
