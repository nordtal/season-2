package eu.nordtal.s2.internalapi;

import eu.nordtal.s2.common.http.Reply;
import eu.nordtal.s2.common.http.WebClient;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import org.jspecify.annotations.Nullable;

/**
 * Steward's way to a service behind an {@link InternalServer}: JSON both ways, the shared secret in its header.
 *
 * An answer passes through unparsed; only a failure is turned into a {@link Failure} that names the service.
 */
public final class InternalClient {

    private final String service;
    private final WebClient web;
    private final String baseUrl;
    private final Duration timeout;

    /**
     * Talks to {@code service} at {@code baseUrl}, waiting at most {@code timeout} for an answer.
     *
     * @throws IllegalArgumentException when a token would travel to {@code baseUrl} in the clear
     */
    public InternalClient(final String service, final String baseUrl, final String token, final Duration timeout) {
        final String trimmed = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.service = service;
        this.baseUrl = token.isBlank() ? trimmed : WebClient.tokenSafe("the address of " + service, trimmed);
        this.timeout = timeout;
        this.web = WebClient.create(timeout).header(InternalServer.TOKEN_HEADER, token);
    }

    /** Returns the compose service this client talks to, as every message names it. */
    public String service() {
        return service;
    }

    public boolean isReachable() {
        return web.answers(uri(InternalServer.HEALTH));
    }

    public String get(final String path) {
        return answered(path, () -> web.get(uri(path), timeout));
    }

    public String post(final String path, final String json) {
        return answered(path, () -> web.post(uri(path), "application/json", json));
    }

    /** Sends one request and answers its body, turning every way it can go wrong into a {@link Failure}. */
    private String answered(final String path, final Exchange exchange) {
        try {
            final Reply reply = exchange.send();
            if (!reply.ok()) {
                // Many of these statuses (a proxy's 307, a bodiless 502) carry no body of their own.
                throw new Failure(
                        service,
                        reply.status(),
                        service + " answered " + reply.status() + " for " + path,
                        reply.body());
            }
            return reply.body();
        } catch (final HttpTimeoutException slow) {
            throw new Failure(
                    service, 504, service + " did not answer " + path + " within " + timeout.toSeconds() + "s", null);
        } catch (final InterruptedIOException interrupted) {
            throw new Failure(service, 503, "interrupted while asking " + service, null);
        } catch (final IOException e) {
            throw new Failure(service, 502, service + " could not be reached at " + baseUrl + " for " + path, null);
        }
    }

    /** One request, sent when {@link #answered} asks for it. */
    @FunctionalInterface
    private interface Exchange {
        Reply send() throws IOException;
    }

    private URI uri(final String path) {
        return URI.create(baseUrl + path);
    }

    /** The service did not answer, or answered with a failure; the message is a sentence to show. */
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

        /** Returns what the service itself said, which is the explanation worth showing, if it said anything. */
        public @Nullable String body() {
            return body;
        }
    }
}
