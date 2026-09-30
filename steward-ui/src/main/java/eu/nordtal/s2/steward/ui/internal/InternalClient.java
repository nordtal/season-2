package eu.nordtal.s2.steward.ui.internal;

import eu.nordtal.s2.common.http.HttpFailure;
import eu.nordtal.s2.common.http.Reply;
import eu.nordtal.s2.common.http.WebClient;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import org.jspecify.annotations.Nullable;

/**
 * Reaches steward-worker and steward-deployer: JSON both ways, the shared secret in {@code X-Steward-Token}.
 *
 * An answer passes through unparsed; only a failure is turned into something to show.
 */
public final class InternalClient {

    private final WebClient web;
    /** The deadline of a log follow, which may sit silent for hours. */
    public static final Duration FOLLOW_DEADLINE = Duration.ofHours(12);

    private final String name;
    private final String baseUrl;
    private final Duration timeout;

    /** Talks to the compose service {@code name} at {@code baseUrl}, waiting at most {@code timeout} for an answer. */
    public InternalClient(final String name, final String baseUrl, final String token, final Duration timeout) {
        this.name = name;
        final String trimmed = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.baseUrl = token.isBlank() ? trimmed : WebClient.tokenSafe(name + ".base-url", trimmed);
        this.timeout = timeout;
        this.web = WebClient.create(timeout).header("X-Steward-Token", token);
    }

    /** Which service this is, for a message that names it. */
    public String name() {
        return name;
    }

    public boolean isReachable() {
        return web.answers(uri("/api/health"));
    }

    public String get(final String path) {
        return get(path, timeout);
    }

    public String get(final String path, final Duration deadline) {
        return answered(path, deadline, () -> web.get(uri(path), deadline));
    }

    public String post(final String path, final String json) {
        return answered(path, timeout, () -> web.post(uri(path), "application/json", json));
    }

    public String delete(final String path) {
        return answered(path, timeout, () -> web.delete(uri(path)));
    }

    public String put(final String path, final String json) {
        return answered(path, timeout, () -> web.put(uri(path), "application/json", json));
    }

    /** Opens an event stream from {@code path} that may stay open for {@link #FOLLOW_DEADLINE}. */
    public InputStream stream(final String path) {
        try {
            return web.stream(uri(path), FOLLOW_DEADLINE, "text/event-stream");
        } catch (final HttpFailure refused) {
            throw new Failure(name, refused.status(), name + " answered " + refused.status() + " for " + path, null);
        } catch (final HttpConnectTimeoutException unanswered) {
            // The handshake is bounded by `timeout`, not FOLLOW_DEADLINE; caught first as a subclass.
            throw new Failure(name, 504, tooSlow(path, timeout), null);
        } catch (final HttpTimeoutException slow) {
            throw new Failure(name, 504, tooSlow(path, FOLLOW_DEADLINE), null);
        } catch (final InterruptedIOException interrupted) {
            throw new Failure(name, 503, "interrupted while streaming from " + name, null);
        } catch (final IOException e) {
            throw new Failure(name, 502, unreachable(path), null);
        }
    }

    /** Sends one request and answers its body, turning every way it can go wrong into a {@link Failure}. */
    private String answered(final String path, final Duration deadline, final Exchange exchange) {
        try {
            final Reply reply = exchange.send();
            if (!reply.ok()) {
                // Many of these statuses (a proxy's 307, a bodiless 502) carry no body of their own.
                throw new Failure(
                        name, reply.status(), name + " answered " + reply.status() + " for " + path, reply.body());
            }
            return reply.body();
        } catch (final HttpTimeoutException slow) {
            throw new Failure(name, 504, tooSlow(path, deadline), null);
        } catch (final InterruptedIOException interrupted) {
            throw new Failure(name, 503, "interrupted while asking " + name, null);
        } catch (final IOException e) {
            throw new Failure(name, 502, unreachable(path), null);
        }
    }

    /** One request to the service, sent when {@link #answered} asks for it. */
    @FunctionalInterface
    private interface Exchange {
        Reply send() throws IOException;
    }

    private String tooSlow(final String path, final Duration deadline) {
        return name + " did not answer " + path + " within " + deadline.toSeconds() + "s";
    }

    private String unreachable(final String path) {
        return name + " could not be reached at " + baseUrl + " for " + path;
    }

    private URI uri(final String path) {
        return URI.create(baseUrl + path);
    }

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
