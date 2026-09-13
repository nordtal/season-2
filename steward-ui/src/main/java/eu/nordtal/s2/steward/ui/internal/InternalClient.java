package eu.nordtal.s2.steward.ui.internal;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * How the interface reaches the two services behind it: steward-worker and steward-deployer.
 *
 * <h2>Why there is a client here at all</h2>
 * §3 says this process holds no docker socket, and it means it: the container runs without one. So
 * every question about a container is an HTTP call to the service that does hold it, over the
 * internal network, with a shared secret. That is a hop the interface would not need if it had the
 * socket - and the hop is the point, because this is the part an attacker reaches first.
 *
 * <h2>One class for both, because it is one protocol</h2>
 * The worker and the deployer speak the same three sentences: JSON in, JSON out, the secret in
 * {@code X-Steward-Token}. They are two services rather than one because they hold different
 * privileges - the deployer may create containers and the worker may not - and that boundary lives
 * in the deployment, not in a second copy of an HTTP client. What the two do not share is their
 * secret: {@link #name} is which of them this instance is, and it is what a failure says out loud,
 * because "steward-deployer is not answering" is a sentence somebody can act on.
 *
 * <h2>It answers with the other service's own JSON, unparsed</h2>
 * Deliberately. The shapes belong over there, the browser is the only consumer, and a DTO in the
 * middle would be a third copy of the same fields that goes stale on the day somebody adds one. The
 * exception is errors: those are turned into something the interface can show, because "the worker
 * said 502" is a sentence and an empty page is not.
 */
public final class InternalClient {

    private final HttpClient http;
    private final String name;
    private final String baseUrl;
    private final String token;

    /**
     * @param name    the compose service this talks to, as it will appear in a failure message
     * @param baseUrl its address on the internal network, with or without a trailing slash
     * @param token   the shared secret, sent as {@code X-Steward-Token}
     * @param timeout how long to wait for the connection - not for the answer, which a log follow
     *                is allowed to take hours over
     */
    public InternalClient(final @NotNull String name, final @NotNull String baseUrl,
                          final @NotNull String token, final @NotNull Duration timeout) {
        this.name = name;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.token = token;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    /** Which service this is, for a message that names it. */
    public @NotNull String name() {
        return name;
    }

    /** Whether it is there at all - asked so the start page can say which half is down. */
    public boolean isReachable() {
        try {
            return http.send(request("/api/health").GET().build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    public @NotNull String get(final @NotNull String path) {
        try {
            final HttpResponse<String> response = http.send(request(path).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new Failure(name, response.statusCode(),
                        name + " answered " + response.statusCode() + " for " + path,
                        response.body());
            }
            return response.body();
        } catch (IOException e) {
            throw new Failure(name, 502, name + " could not be reached at " + baseUrl, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Failure(name, 503, "interrupted while asking " + name, null);
        }
    }

    public @NotNull String post(final @NotNull String path, final @NotNull String json) {
        try {
            final HttpResponse<String> response = http.send(request(path)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(json)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new Failure(name, response.statusCode(), response.body(), response.body());
            }
            return response.body();
        } catch (IOException e) {
            throw new Failure(name, 502, name + " could not be reached at " + baseUrl, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Failure(name, 503, "interrupted while asking " + name, null);
        }
    }

    /**
     * Opens a stream and hands the caller the body to read.
     *
     * <p>Used for the log follow, which is an SSE stream on both sides: the worker sends events,
     * this reads them, and the browser is given the same events again. A proxy rather than a
     * redirect because the browser must never be given the worker's address or its token.</p>
     */
    public @NotNull InputStream stream(final @NotNull String path) {
        try {
            final HttpResponse<InputStream> response = http.send(
                    request(path).header("Accept", "text/event-stream").GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() >= 400) {
                throw new Failure(name, response.statusCode(),
                        name + " answered " + response.statusCode() + " for " + path, null);
            }
            return response.body();
        } catch (IOException e) {
            throw new Failure(name, 502, name + " could not be reached at " + baseUrl, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Failure(name, 503, "interrupted while streaming from " + name, null);
        }
    }

    private HttpRequest.Builder request(final String path) {
        return HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("X-Steward-Token", token)
                // Long, because a log follow is supposed to stay open for hours. Every call that is
                // not a stream carries its own shorter deadline through the server it talks to.
                .timeout(Duration.ofHours(12));
    }

    /** What the interface shows when one of the two will not answer. */
    public static final class Failure extends RuntimeException {

        private final String where;
        private final int status;
        private final @Nullable String body;

        Failure(final String where, final int status, final String message,
                final @Nullable String body) {
            super(message);
            this.where = where;
            this.status = status;
            this.body = body;
        }

        /** Which service did not answer. The interface shows it, so it must not be a guess. */
        public @NotNull String where() {
            return where;
        }

        public int status() {
            return status;
        }

        public @Nullable String body() {
            return body;
        }
    }
}
