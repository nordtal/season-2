package eu.nordtal.season.internalapi;

import eu.nordtal.season.common.http.HttpFailure;
import eu.nordtal.season.common.http.Reply;
import eu.nordtal.season.common.http.WebClient;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import org.jspecify.annotations.Nullable;

/**
 * The client of steward for a service behind an {@link InternalServer}: JSON both ways, the shared secret in a header.
 * Only a failure is parsed, into a {@link Failure}.
 */
public final class InternalClient {

    private final String service;
    private final WebClient web;
    private final String baseUrl;
    private final Duration timeout;

    /**
     * Calls {@code service} at {@code baseUrl} and waits at most {@code timeout} for an answer.
     *
     * @throws IllegalArgumentException if a token would travel to {@code baseUrl} unencrypted
     */
    public InternalClient(final String service, final String baseUrl, final String token, final Duration timeout) {
        final String trimmed = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.service = service;
        this.baseUrl = token.isBlank() ? trimmed : WebClient.tokenSafe("the address of " + service, trimmed);
        this.timeout = timeout;
        this.web = WebClient.create(timeout).header(InternalServer.TOKEN_HEADER, token);
    }

    public String service() {
        return service;
    }

    public boolean isReachable() {
        return web.answers(uri(InternalServer.HEALTH));
    }

    public String get(final String path) {
        return get(path, timeout);
    }

    /** Sends a GET that may take {@code within} to answer. */
    public String get(final String path, final Duration within) {
        return answered(path, within, () -> web.get(uri(path), within));
    }

    public String post(final String path, final String json) {
        return post(path, json, timeout);
    }

    /** Sends a POST that may take {@code within} to answer. */
    public String post(final String path, final String json, final Duration within) {
        return answered(path, within, () -> web.post(uri(path), "application/json", json, within));
    }

    /**
     * Opens a GET whose body is read as it arrives, until the caller closes it.
     *
     * @param accept the media type asked for, {@code text/event-stream} for events
     */
    public InputStream stream(final String path, final String accept) {
        try {
            return web.stream(uri(path), timeout, accept);
        } catch (final HttpFailure refused) {
            throw new Failure(
                    service, refused.status(), service + " answered " + refused.status() + " for " + path, null);
        } catch (final IOException e) {
            throw failureOf(path, timeout, e);
        }
    }

    /** Sends one request and returns its body. Every failure becomes a {@link Failure}. */
    private String answered(final String path, final Duration within, final Exchange exchange) {
        try {
            final Reply reply = exchange.send();
            if (!reply.ok()) {
                throw new Failure(
                        service,
                        reply.status(),
                        service + " answered " + reply.status() + " for " + path,
                        reply.body());
            }
            return reply.body();
        } catch (final IOException e) {
            throw failureOf(path, within, e);
        }
    }

    /** Returns the {@link Failure} for a request without an answer: too slow, interrupted or nobody there. */
    private Failure failureOf(final String path, final Duration within, final IOException e) {
        if (e instanceof HttpTimeoutException) {
            return new Failure(
                    service, 504, service + " did not answer " + path + " within " + within.toSeconds() + "s", null);
        }
        if (e instanceof InterruptedIOException) {
            return new Failure(service, 503, "interrupted while asking " + service, null);
        }
        return new Failure(service, 502, service + " could not be reached at " + baseUrl + " for " + path, null);
    }

    @FunctionalInterface
    private interface Exchange {
        Reply send() throws IOException;
    }

    private URI uri(final String path) {
        return URI.create(baseUrl + path);
    }

    /** The service did not answer or answered with a failure. The message is a sentence to show. */
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

        /**
         * Returns a 502 that names the service behind the one called, for example the Docker daemon.
         */
        public static Failure behind(final String where, final String message) {
            return new Failure(where, 502, message, null);
        }

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
