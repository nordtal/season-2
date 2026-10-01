package eu.nordtal.s2.steward.agent;

import eu.nordtal.s2.common.http.Reply;
import eu.nordtal.s2.common.http.WebClient;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import org.jspecify.annotations.Nullable;

/**
 * Steward's one way to steward-agent: JSON both ways, the shared secret in {@code X-Steward-Token}.
 *
 * An answer passes through unparsed; only a failure is turned into a {@link Failure} that names the agent.
 */
public final class AgentClient {

    /** The compose service, named in every message, since the interface shows which service did not answer. */
    public static final String NAME = "steward-agent";

    private final WebClient web;
    private final String baseUrl;
    private final Duration timeout;

    /** Talks to the agent at {@code baseUrl}, waiting at most {@code timeout} for an answer. */
    public AgentClient(final String baseUrl, final String token, final Duration timeout) {
        final String trimmed = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.baseUrl = token.isBlank() ? trimmed : WebClient.tokenSafe(NAME + ".base-url", trimmed);
        this.timeout = timeout;
        this.web = WebClient.create(timeout).header("X-Steward-Token", token);
    }

    public boolean isReachable() {
        return web.answers(uri("/api/health"));
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
                throw new Failure(reply.status(), NAME + " answered " + reply.status() + " for " + path, reply.body());
            }
            return reply.body();
        } catch (final HttpTimeoutException slow) {
            throw new Failure(504, NAME + " did not answer " + path + " within " + timeout.toSeconds() + "s", null);
        } catch (final InterruptedIOException interrupted) {
            throw new Failure(503, "interrupted while asking " + NAME, null);
        } catch (final IOException e) {
            throw new Failure(502, NAME + " could not be reached at " + baseUrl + " for " + path, null);
        }
    }

    /** One request to the agent, sent when {@link #answered} asks for it. */
    @FunctionalInterface
    private interface Exchange {
        Reply send() throws IOException;
    }

    private URI uri(final String path) {
        return URI.create(baseUrl + path);
    }

    /** The agent did not answer, or answered with a failure; the message is a sentence to show. */
    public static final class Failure extends RuntimeException {

        private final int status;
        private final @Nullable String body;

        Failure(final int status, final String message, final @Nullable String body) {
            super(message);
            this.status = status;
            this.body = body;
        }

        /** Which service did not answer; the interface shows it, so it must not be a guess. */
        public String where() {
            return NAME;
        }

        public int status() {
            return status;
        }

        public @Nullable String body() {
            return body;
        }
    }
}
