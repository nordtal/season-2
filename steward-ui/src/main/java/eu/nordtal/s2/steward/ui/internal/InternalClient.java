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
 * How the interface reaches the two services behind it: steward-worker and steward-deployer.
 *
 * Both speak JSON in, JSON out, with the shared secret in {@code X-Steward-Token}, and both answer
 * with the other service's own JSON, unparsed; only a failure is turned into something to show.
 */
public final class InternalClient {

    private final HttpClient http;
    /** What a log follow may take: it is supposed to sit there saying nothing for hours. */
    public static final Duration FOLLOW_DEADLINE = Duration.ofHours(12);

    private final String name;
    private final String baseUrl;
    private final String token;
    private final Duration timeout;

    /**
     * @param name    the compose service this talks to, as it will appear in a failure message
     * @param baseUrl its address on the internal network, with or without a trailing slash
     * @param token   the shared secret, sent as {@code X-Steward-Token}
     * @param timeout how long to wait, both for the connection and for an ordinary answer. A
     *                stream is the exception and carries {@link #FOLLOW_DEADLINE} instead, because
     *                a log follow is allowed to take hours over saying nothing
     */
    public InternalClient(final String name, final String baseUrl, final String token, final Duration timeout) {
        this.name = name;
        final String trimmed = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.baseUrl = token.isBlank() ? trimmed : plaintextOnlyInside(name, trimmed);
        this.token = token;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    /**
     * Refuses to send a token in clear to an address that is not inside this deployment.
     *
     * Plain {@code http} is allowed to a compose service name or to loopback; anything else must be
     * {@code https}, so an editable {@code base-url} cannot leak the token to a public address.
     */
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
     * Whether a host is somewhere this network can still be called internal.
     *
     * {@code URI.getHost()} keeps the brackets on an IPv6 literal, so it is checked here rather
     * than falling through to the dotless-name rule, which would wave any such literal through.
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

    /** Whether it is there at all - asked so the start page can say which half is down. */
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

    /**
     * The same, for the one kind of read whose work is the waiting.
     *
     * The deadline is an argument rather than a field, since a call like a forced cache refresh is
     * legitimately slower than the configured timeout, which is sized for an answer out of memory.
     */
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

    /** The same, as a {@code DELETE}, and with no body in either direction. */
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

    /** The same, as a {@code PUT}. */
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
     * Opens a stream for the log follow, a proxy rather than a redirect.
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
     * A timeout's own sentence, told apart from an unreachable service: too slow is not down.
     *
     * {@link HttpTimeoutException} extends {@link IOException}, so the two must be caught in order.
     */
    private String tooSlow(final String path, final Duration deadline) {
        return name + " did not answer " + path + " within " + deadline.toSeconds() + "s";
    }

    private String unreachable(final String path) {
        return name + " could not be reached at " + baseUrl + " for " + path;
    }

    /**
     * A request that has to be answered within the configured timeout.
     *
     * {@code connectTimeout} bounds only the TCP handshake, not the answer, hence the explicit
     * per-request {@code deadline} passed here.
     */
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

        /** Which service did not answer. The interface shows it, so it must not be a guess. */
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
