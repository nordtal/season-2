package eu.nordtal.s2.steward.worker.http;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The real {@link Http}: {@code java.net.http}, following redirects except HTTPS to HTTP.
 *
 * Fill requires an identifying User-Agent, and GitHub release assets answer with a redirect.
 */
public final class JdkHttp implements Http {

    /** How this process identifies itself to GitHub, Modrinth and the Fill API. */
    public static final String USER_AGENT = "nordtal-season-2/steward-worker (+https://github.com/nordtal/season-2)";

    private final HttpClient client;
    private final Duration timeout;
    private final Map<String, String> headers;

    /**
     * Builds the client.
     *
     * @param token an optional GitHub token, empty for none, sent on every request
     */
    public JdkHttp(final Duration timeout, final String token) {
        this.timeout = timeout;
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(timeout)
                .build();

        final Map<String, String> defaults = new LinkedHashMap<>();
        defaults.put("User-Agent", USER_AGENT);
        defaults.put("Accept", "application/json");
        if (token != null && !token.isBlank()) {
            defaults.put("Authorization", "Bearer " + token);
        }
        this.headers = Map.copyOf(defaults);
    }

    @Override
    public String get(final URI uri) throws IOException {
        final HttpRequest.Builder request = HttpRequest.newBuilder(uri).GET().timeout(timeout);
        headers.forEach(request::header);

        final HttpResponse<String> response;
        try {
            response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (final InterruptedException interrupted) {
            // Restoring the flag matters: a resolve runs on a worker that a shutdown wants back.
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while fetching " + uri, interrupted);
        }

        if (response.statusCode() / 100 != 2) {
            throw new HttpException(uri, response.statusCode(), response.body());
        }
        return response.body();
    }
}
