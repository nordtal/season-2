package eu.nordtal.s2.updater.http;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The real {@link Http}: {@code java.net.http}, no dependency, redirects followed.
 *
 * <h2>The User-Agent is not decoration</h2>
 * The PaperMC Fill API <em>requires</em> a User-Agent that identifies the project and gives a
 * contact, and refuses requests without one; Modrinth asks for the same in its documentation and
 * throttles anonymous traffic harder. {@code deploy/minecraft/entrypoint.sh} already sends exactly
 * this string, so the two halves of this deployment identify themselves as one thing.
 *
 * <h2>Redirects</h2>
 * {@link HttpClient.Redirect#NORMAL} follows them, and it has to: a GitHub release asset - which is
 * how the pack's {@code .sha1} is read - answers with a 302 to a signed
 * {@code release-assets.githubusercontent.com} URL. {@code NORMAL} does not follow HTTPS to HTTP,
 * which is the one redirect we would want to refuse anyway.
 */
public final class JdkHttp implements Http {

    /** The same identification {@code deploy/minecraft/entrypoint.sh} sends. */
    public static final String USER_AGENT = "nordtal-season-2/updater (+https://github.com/nordtal/season-2)";

    private final HttpClient client;
    private final Duration timeout;
    private final Map<String, String> headers;

    /**
     * @param token an optional GitHub token. Empty for none - it is sent on every request, which is
     *              safe because the only hosts this module talks to are GitHub, Modrinth and
     *              PaperMC, and unhelpful nowhere.
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
    public @NotNull String get(final @NotNull URI uri) throws IOException {
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
