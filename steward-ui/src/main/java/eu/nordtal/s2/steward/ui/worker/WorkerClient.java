package eu.nordtal.s2.steward.ui.worker;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * How the interface reaches the daemon: by asking steward-worker.
 *
 * <h2>Why there is a client here at all</h2>
 * §3 says this process holds no docker socket, and it means it: the container runs without one. So
 * every question about a container is an HTTP call to the service that does hold it, over the
 * internal network, with a shared secret. That is a hop the interface would not need if it had the
 * socket - and the hop is the point, because this is the part an attacker reaches first.
 *
 * <h2>It answers with the worker's own JSON, unparsed</h2>
 * Deliberately. The shapes belong to the worker, the browser is the only consumer, and a DTO in the
 * middle would be a third copy of the same fields that goes stale on the day somebody adds one. The
 * exception is errors: those are turned into something the interface can show, because "the worker
 * said 502" is a sentence and an empty page is not.
 */
public final class WorkerClient {

    private static final Logger log = LoggerFactory.getLogger(WorkerClient.class);

    private final HttpClient http;
    private final String baseUrl;
    private final String token;

    public WorkerClient(final @NotNull String baseUrl, final @NotNull String token,
                        final @NotNull Duration timeout) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.token = token;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    /** Whether the worker is there at all - asked so the start page can say which half is down. */
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
                throw new WorkerException(response.statusCode(),
                        "steward-worker answered " + response.statusCode() + " for " + path,
                        response.body());
            }
            return response.body();
        } catch (IOException e) {
            throw new WorkerException(502, "steward-worker could not be reached at " + baseUrl, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WorkerException(503, "interrupted while asking steward-worker", null);
        }
    }

    public @NotNull String post(final @NotNull String path, final @NotNull String json) {
        try {
            final HttpResponse<String> response = http.send(request(path)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(json)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new WorkerException(response.statusCode(), response.body(), response.body());
            }
            return response.body();
        } catch (IOException e) {
            throw new WorkerException(502, "steward-worker could not be reached at " + baseUrl, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WorkerException(503, "interrupted while asking steward-worker", null);
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
                throw new WorkerException(response.statusCode(),
                        "steward-worker answered " + response.statusCode() + " for " + path, null);
            }
            return response.body();
        } catch (IOException e) {
            throw new WorkerException(502, "steward-worker could not be reached at " + baseUrl, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WorkerException(503, "interrupted while streaming from steward-worker", null);
        }
    }

    private HttpRequest.Builder request(final String path) {
        return HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("X-Steward-Token", token)
                // Long, because a log follow is supposed to stay open for hours. Every call that is
                // not a stream carries its own shorter deadline through the server it talks to.
                .timeout(Duration.ofHours(12));
    }

    /** What the interface shows when the worker will not answer. */
    public static final class WorkerException extends RuntimeException {

        private final int status;
        private final @Nullable String body;

        WorkerException(final int status, final String message, final @Nullable String body) {
            super(message);
            this.status = status;
            this.body = body;
        }

        public int status() {
            return status;
        }

        public @Nullable String body() {
            return body;
        }
    }
}
