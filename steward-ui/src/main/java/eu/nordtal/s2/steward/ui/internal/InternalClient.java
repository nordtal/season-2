package eu.nordtal.s2.steward.ui.internal;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import org.jspecify.annotations.Nullable;

/**
 * Reaches steward-worker and steward-deployer: JSON both ways, the shared secret in {@code X-Steward-Token}.
 *
 * An answer passes through unparsed; only a failure is turned into something to show.
 */
public final class InternalClient {

    private final HttpClient http;
    /** The deadline of a log follow, which may sit silent for hours. */
    public static final Duration FOLLOW_DEADLINE = Duration.ofHours(12);

    private final String name;
    private final String baseUrl;
    private final String token;
    private final Duration timeout;

    /** Talks to the compose service {@code name} at {@code baseUrl}, waiting at most {@code timeout} for an answer. */
    public InternalClient(final String name, final String baseUrl, final String token, final Duration timeout) {
        this.name = name;
        final String trimmed = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.baseUrl = token.isBlank() ? trimmed : plaintextOnlyInside(name, trimmed);
        this.token = token;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    /** Refuses plain {@code http} to anything but a compose service name or loopback, so the token cannot leak. */
    private static String plaintextOnlyInside(final String name, final String baseUrl) {
        final URI uri = URI.create(baseUrl);
        if ("https".equalsIgnoreCase(uri.getScheme())) {
            return baseUrl;
        }
        final String host = uri.getHost();
        if ("http".equalsIgnoreCase(uri.getScheme()) && host != null && isInside(host)) {
            return baseUrl;
        }
        throw new IllegalArgumentException(name + ".base-url is " + baseUrl + ", and this process"
                + " will not send its token there in clear. It is https, or plain http to a"
                + " compose service name on the internal network - which is what the default"
                + " http://" + name + ":8081 is.");
    }

    /**
     * Whether {@code host} is a compose service name or loopback.
     *
     * {@code URI.getHost()} keeps the brackets on an IPv6 literal, which the dotless name rule would wave through.
     */
    private static boolean isInside(final String host) {
        if (host.startsWith("[")) {
            return "[::1]".equals(host) || "[0:0:0:0:0:0:0:1]".equalsIgnoreCase(host);
        }
        // A name with no dot in it cannot be a public DNS name, so it is a compose service.
        return !host.contains(".") || "127.0.0.1".equals(host);
    }

    /** Which service this is, for a message that names it. */
    public String name() {
        return name;
    }

    /** Whether the service answers at all, so the start page can say which half is down. */
    public boolean isReachable() {
        try {
            return http.send(request("/api/health").GET().build(), HttpResponse.BodyHandlers.discarding())
                            .statusCode()
                    == 200;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    public String get(final String path) {
        return get(path, timeout);
    }

    /** Reads {@code path} with its own deadline, for a read legitimately slower than the configured timeout. */
    public String get(final String path, final Duration deadline) {
        try {
            final HttpResponse<String> response =
                    http.send(request(path, deadline).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (isNotSuccess(response.statusCode())) {
                throw new Failure(
                        name,
                        response.statusCode(),
                        name + " answered " + response.statusCode() + " for " + path,
                        response.body());
            }
            return response.body();
        } catch (HttpTimeoutException slow) {
            throw new Failure(name, 504, tooSlow(path, deadline), null);
        } catch (IOException e) {
            throw new Failure(name, 502, unreachable(path), null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Failure(name, 503, "interrupted while asking " + name, null);
        }
    }

    public String post(final String path, final String json) {
        try {
            final HttpResponse<String> response = http.send(
                    request(path)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(json))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (isNotSuccess(response.statusCode())) {
                // Many of these statuses (a proxy's 307, a bodiless 502) carry no body of their own.
                throw new Failure(
                        name,
                        response.statusCode(),
                        name + " answered " + response.statusCode() + " for " + path,
                        response.body());
            }
            return response.body();
        } catch (HttpTimeoutException slow) {
            throw new Failure(name, 504, tooSlow(path, timeout), null);
        } catch (IOException e) {
            throw new Failure(name, 502, unreachable(path), null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Failure(name, 503, "interrupted while asking " + name, null);
        }
    }

    /** Sends a {@code DELETE}, with no body in either direction. */
    public String delete(final String path) {
        try {
            final HttpResponse<String> response =
                    http.send(request(path).DELETE().build(), HttpResponse.BodyHandlers.ofString());
            if (isNotSuccess(response.statusCode())) {
                throw new Failure(
                        name,
                        response.statusCode(),
                        name + " answered " + response.statusCode() + " for " + path,
                        response.body());
            }
            return response.body();
        } catch (HttpTimeoutException slow) {
            throw new Failure(name, 504, tooSlow(path, timeout), null);
        } catch (IOException e) {
            throw new Failure(name, 502, unreachable(path), null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Failure(name, 503, "interrupted while asking " + name, null);
        }
    }

    /** Sends {@code json} as a {@code PUT}. */
    public String put(final String path, final String json) {
        try {
            final HttpResponse<String> response = http.send(
                    request(path)
                            .header("Content-Type", "application/json")
                            .PUT(HttpRequest.BodyPublishers.ofString(json))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (isNotSuccess(response.statusCode())) {
                throw new Failure(
                        name,
                        response.statusCode(),
                        name + " answered " + response.statusCode() + " for " + path,
                        response.body());
            }
            return response.body();
        } catch (HttpTimeoutException slow) {
            throw new Failure(name, 504, tooSlow(path, timeout), null);
        } catch (IOException e) {
            throw new Failure(name, 502, unreachable(path), null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Failure(name, 503, "interrupted while asking " + name, null);
        }
    }

    /**
     * Opens a stream for the log follow, proxied rather than redirected.
     *
     * Closing it does not close the connection until traffic next moves on it.
     */
    public InputStream stream(final String path) {
        try {
            final HttpResponse<InputStream> response = http.send(
                    request(path, FOLLOW_DEADLINE)
                            .header("Accept", "text/event-stream")
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            if (isNotSuccess(response.statusCode())) {
                // ofInputStream hands back an open body for a failure too; drain it, do not leak it.
                try (InputStream refused = response.body()) {
                    refused.readAllBytes();
                } catch (IOException ignored) {
                    // The status is the diagnosis; a body we could not drain does not change it.
                }
                throw new Failure(
                        name,
                        response.statusCode(),
                        name + " answered " + response.statusCode() + " for " + path,
                        null);
            }
            return response.body();
        } catch (HttpConnectTimeoutException unanswered) {
            // The handshake is bounded by `timeout`, not FOLLOW_DEADLINE; caught first as a subclass.
            throw new Failure(name, 504, tooSlow(path, timeout), null);
        } catch (HttpTimeoutException slow) {
            throw new Failure(name, 504, tooSlow(path, FOLLOW_DEADLINE), null);
        } catch (IOException e) {
            throw new Failure(name, 502, unreachable(path), null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Failure(name, 503, "interrupted while streaming from " + name, null);
        }
    }

    /** Anything that is not 2xx, including a redirect: this client's policy is {@code NEVER}. */
    private static boolean isNotSuccess(final int status) {
        return status < 200 || status >= 300;
    }

    /**
     * Says the service was too slow, which is not the same as down.
     *
     * {@link HttpTimeoutException} extends {@link IOException}, so the two must be caught in that order.
     */
    private String tooSlow(final String path, final Duration deadline) {
        return name + " did not answer " + path + " within " + deadline.toSeconds() + "s";
    }

    private String unreachable(final String path) {
        return name + " could not be reached at " + baseUrl + " for " + path;
    }

    /** A request bounded by the configured timeout, since {@code connectTimeout} bounds only the handshake. */
    private HttpRequest.Builder request(final String path) {
        return request(path, timeout);
    }

    private HttpRequest.Builder request(final String path, final Duration deadline) {
        return HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("X-Steward-Token", token)
                .timeout(deadline);
    }

    /** What the interface shows when one of the two will not answer. */
    public static final class Failure extends RuntimeException {

        private final String where;
        private final int status;
        private final @Nullable String body;

        Failure(final String where, final int status, final String message, final @Nullable String body) {
            super(message);
            this.where = where;
            this.status = status;
            this.body = body;
        }

        /** Which service did not answer; the interface shows it, so it must not be a guess. */
        public String where() {
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
