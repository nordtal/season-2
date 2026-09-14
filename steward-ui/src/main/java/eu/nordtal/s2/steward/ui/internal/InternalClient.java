package eu.nordtal.s2.steward.ui.internal;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
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
    public InternalClient(final @NotNull String name, final @NotNull String baseUrl,
                          final @NotNull String token, final @NotNull Duration timeout) {
        this.name = name;
        final String trimmed = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.baseUrl = token.isBlank() ? trimmed : plaintextOnlyInside(name, trimmed);
        this.token = token;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    /**
     * Refuses to send a token in clear to an address that is not inside this deployment.
     *
     * <p>There is no TLS between these three containers and there is deliberately not going to be:
     * they share one Docker network on one host, and a certificate authority for a link that never
     * leaves the machine would be a second thing to renew for no attacker it stops. Somebody who
     * can read that network already has the host, and with it the docker socket the deployer is
     * holding.</p>
     *
     * <p>What this does stop is the configuration mistake. {@code base-url} is a setting, it is
     * editable from the interface itself, and pointing it at a public address would put the
     * deployer's token - the one credential in this stack that may create containers - in clear on
     * the way there, with nothing anywhere saying so. So plain {@code http} is allowed to a name
     * with no dot in it (a compose service, which cannot be a public DNS name) or to loopback, and
     * anything else has to be {@code https}. A client with no token configured is not checked at
     * all: it sends no secret, and it is already refusing to do anything.</p>
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
     * <p><b>An IPv6 literal is its own case, and it has to be</b>: {@code URI.getHost()} answers
     * {@code [::1]} <em>with</em> the brackets, so a bare {@code "::1".equals(host)} never matched
     * anything - and worse, a literal contains no dot, so the compose-service rule below would have
     * waved {@code http://[2001:db8::1]:8081} through as if it were a service name on this host.
     * A bracketed host is therefore decided here and nowhere else, and only loopback passes.</p>
     */
    private static boolean isInside(final String host) {
        if (host.startsWith("[")) {
            return "[::1]".equals(host) || "[0:0:0:0:0:0:0:1]".equalsIgnoreCase(host);
        }
        // A name with no dot in it cannot be a public DNS name, so it is a compose service.
        return !host.contains(".") || "127.0.0.1".equals(host);
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
            if (isNotSuccess(response.statusCode())) {
                throw new Failure(name, response.statusCode(),
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

    public @NotNull String post(final @NotNull String path, final @NotNull String json) {
        try {
            final HttpResponse<String> response = http.send(request(path)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(json)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (isNotSuccess(response.statusCode())) {
                // The same sentence `get` builds. The body used to be the message, and most of the
                // statuses this now catches have no body at all - a 307 from a proxy, a bodiless
                // 502 - so the browser was handed {"error":""} and the log line ended in a colon.
                throw new Failure(name, response.statusCode(),
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
     * The same, as a {@code PUT}.
     *
     * <p>One verb and one method, rather than a parameter: the two calls differ in nothing else,
     * and a {@code method} argument is how a {@code POST} eventually gets sent to a route that
     * only accepts {@code PUT} with the failure showing up as a 405 nobody can place.</p>
     */
    public @NotNull String put(final @NotNull String path, final @NotNull String json) {
        try {
            final HttpResponse<String> response = http.send(request(path)
                            .header("Content-Type", "application/json")
                            .PUT(HttpRequest.BodyPublishers.ofString(json)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (isNotSuccess(response.statusCode())) {
                throw new Failure(name, response.statusCode(),
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
     * Opens a stream and hands the caller the body to read.
     *
     * <p>Used for the log follow, which is an SSE stream on both sides: the worker sends events,
     * this reads them, and the browser is given the same events again. A proxy rather than a
     * redirect because the browser must never be given the worker's address or its token.</p>
     *
     * <h2>Closing the returned stream is not, on its own, closing the connection</h2>
     * Measured on this host on 2026-09-13: closing the {@code InputStream} of a response cancels
     * the subscription, and the JDK's client tears the connection down when something next happens
     * on it - an arriving byte, an error. On a stream that says <em>nothing</em> that moment never
     * comes, and the socket stays open with nobody reading it.
     *
     * <p>That is why {@code steward-worker} sends a comment every ten seconds on a follow, and it
     * is not only politeness at the other end: it is what makes a cancellation here actually land,
     * within one beat. The two are one mechanism and they ship in one version together.</p>
     */
    public @NotNull InputStream stream(final @NotNull String path) {
        try {
            final HttpResponse<InputStream> response = http.send(
                    request(path, FOLLOW_DEADLINE).header("Accept", "text/event-stream").GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            if (isNotSuccess(response.statusCode())) {
                // ofInputStream hands back an open body for a failure too, and this branch used to
                // drop it on the floor: a connection to the worker held open by a request that was
                // already refused, one per rejected follow.
                try (InputStream refused = response.body()) {
                    refused.readAllBytes();
                } catch (IOException ignored) {
                    // The status is the diagnosis; a body we could not drain does not change it.
                }
                throw new Failure(name, response.statusCode(),
                        name + " answered " + response.statusCode() + " for " + path, null);
            }
            return response.body();
        } catch (HttpConnectTimeoutException unanswered) {
            // Connect is bounded by `timeout` even here: the twelve hours are the request's
            // deadline, and the handshake never had them. Caught first because it is a subclass -
            // without this line a refused handshake after ten seconds reported "within 43200s".
            throw new Failure(name, 504, tooSlow(path, timeout), null);
        } catch (HttpTimeoutException slow) {
            // FOLLOW_DEADLINE, not `timeout` - a follow is allowed twelve hours and the sentence
            // has to say the number that actually ran out.
            throw new Failure(name, 504, tooSlow(path, FOLLOW_DEADLINE), null);
        } catch (IOException e) {
            throw new Failure(name, 502, unreachable(path), null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Failure(name, 503, "interrupted while streaming from " + name, null);
        }
    }

    /**
     * Anything that is not 2xx, which includes the redirect nobody is going to follow.
     *
     * <p>It was {@code >= 400}, and the gap in the middle had a real answer in it: this client is
     * built with the JDK's default redirect policy, which is {@code NEVER}. A proxy in front of
     * the deployer answering {@code 307} therefore came back here as a success with an empty body,
     * and {@code DeployerApi.recreate} reported {@code 202} for a job that was never accepted.</p>
     */
    private static boolean isNotSuccess(final int status) {
        return status < 200 || status >= 300;
    }

    /**
     * The two sentences a failed call can end in, and they are not the same evening.
     *
     * <p><b>They were one sentence until 2026-09-14, and it was the wrong one.</b>
     * {@link HttpTimeoutException} extends {@link IOException}, so a service that accepted the
     * connection and answered a second too late was reported as a service that could not be
     * reached at all. Measured on the dev host that day: steward-ui logged
     * {@code steward-worker could not be reached at http://steward-worker:8082} about once a
     * minute, for hours, about a container that was healthy and answering - because
     * {@code GET /api/services} takes 11.5 s whenever steward-worker's one-minute drift cache has
     * expired, against the 10 s deadline here. An operator reading that goes looking at a network
     * that is fine.</p>
     *
     * <p>Both name the path now. The log line is one line and used to name only the service, which
     * is the half a reader already knows - what they cannot find out from anywhere else is
     * <em>which</em> of a dozen calls is the slow one.</p>
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
     * <p><b>connectTimeout is not a deadline.</b> It bounds the TCP handshake and nothing after it,
     * so a worker that accepts a connection and then stops answering - a daemon mid-restart, a
     * thread pool that has filled - left an ordinary page request hanging for the twelve hours that
     * belong to the log follow. The comment here used to claim every non-stream call carried its
     * own shorter deadline "through the server it talks to", and nothing did.</p>
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
