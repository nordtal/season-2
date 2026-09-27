package eu.nordtal.s2.steward.ui;

import eu.nordtal.s2.steward.ui.auth.DiscordAuth;
import eu.nordtal.s2.steward.ui.auth.Sessions;
import eu.nordtal.s2.steward.ui.internal.InternalClient;
import io.javalin.http.Context;
import io.javalin.http.sse.SseClient;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** {@code /api/services/{name}/logs}: the log follow, proxied to the browser line by line. */
final class LogFollow {

    private static final Logger log = LoggerFactory.getLogger(LogFollow.class);

    /**
     * How often an open log follow says something into the browser's connection.
     *
     * A disconnected browser is otherwise discovered only by a failing write, and the comment also
     * keeps a reverse proxy from dropping an idle stream.
     */
    private static final Duration HEARTBEAT = Duration.ofSeconds(10);

    /** How often a running follow asks the database whether its session still exists. */
    private static final long SESSION_RECHECK_NANOS = TimeUnit.SECONDS.toNanos(1);

    /** The worker's log events the browser is handed under their own name. */
    private static final Set<String> FORWARDED_EVENTS = Set.of("line", "run", "end");

    private final InternalClient worker;
    private final @Nullable Sessions sessions;
    private final Function<Context, Optional<DiscordAuth.Account>> accounts;
    private final ExecutorService streams;
    private final ScheduledExecutorService heartbeats;

    LogFollow(
            final InternalClient worker,
            final @Nullable Sessions sessions,
            final Function<Context, Optional<DiscordAuth.Account>> accounts,
            final ExecutorService streams,
            final ScheduledExecutorService heartbeats) {
        this.worker = worker;
        this.sessions = sessions;
        this.accounts = accounts;
        this.streams = streams;
        this.heartbeats = heartbeats;
    }

    /** The log follow, proxied line by line rather than redirected, so the browser never sees the worker. */
    void serve(final SseClient client) {
        if (accounts.apply(client.ctx()).isEmpty()) {
            client.close();
            return;
        }
        final String name = client.ctx().pathParam("name");
        final String query = client.ctx().queryString();
        // Registered before the follow is submitted, so a browser that leaves early has something to cancel.
        final Upstream upstream = new Upstream();
        final ScheduledFuture<?> heartbeat = heartbeats.scheduleWithFixedDelay(
                () -> client.sendComment("open"), HEARTBEAT.toSeconds(), HEARTBEAT.toSeconds(), TimeUnit.SECONDS);
        client.onClose(() -> {
            heartbeat.cancel(false);
            upstream.close();
        });
        client.keepAlive();
        final var _ = streams.submit(() -> follow(client, upstream, name, query));
    }

    private void follow(final SseClient client, final Upstream upstream, final String name, final String query) {
        final String path = "/api/services/" + name + "/logs" + StewardUi.forwardedQuery(query);
        try (InputStream stream = worker.stream(path);
                BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            upstream.hold(stream);
            String line;
            String event = "line";
            long checked = System.nanoTime() - SESSION_RECHECK_NANOS;
            while ((line = reader.readLine()) != null) {
                // Javalin does not throw on a terminated client, so this end has to notice on its own.
                if (client.terminated()) {
                    return;
                }
                // Re-checked at most once a second, since a follow outlives the sign-in check at open.
                final long now = System.nanoTime();
                if (now - checked >= SESSION_RECHECK_NANOS) {
                    if (!stillSignedIn(client)) {
                        client.sendEvent("gone", "this session ended - sign in again to keep watching");
                        return;
                    }
                    checked = now;
                }
                // Re-emits the worker's own SSE events; only names this end knows are passed on.
                if (line.startsWith("event:")) {
                    final String named = line.substring(6).strip();
                    event = FORWARDED_EVENTS.contains(named) ? named : "line";
                } else if (line.startsWith("data:")) {
                    client.sendEvent(event, line.substring(5).stripLeading());
                } else if (line.isEmpty()) {
                    event = "line";
                }
            }
        } catch (IOException | InternalClient.Failure e) {
            // A stream this end closed on purpose fails the in-flight read; that is not an outage.
            if (!upstream.wasClosed()) {
                client.sendEvent("gone", "the log stream ended: " + e.getMessage());
            }
        } finally {
            client.close();
        }
    }

    /**
     * Whoever is watching, still allowed to.
     *
     * Reads the row rather than a parked session, since a follow outlives the parking; a database
     * failure answers no rather than throwing.
     */
    private boolean stillSignedIn(final SseClient client) {
        if (sessions == null) {
            return false;
        }
        try {
            return sessions.find(client.ctx().cookie(Sessions.COOKIE)).isPresent();
        } catch (RuntimeException gone) {
            log.warn("could not re-check a log follower's session, so it is being ended: {}", gone.getMessage());
            return false;
        }
    }

    /**
     * The worker's end of one log follow, held so whoever notices the browser has gone can close it.
     *
     * The two halves open on different threads, and either can finish first.
     */
    private static final class Upstream {

        private @Nullable InputStream stream;
        private boolean closed;

        synchronized void hold(final InputStream open) {
            stream = open;
            if (closed) {
                shut(open);
            }
        }

        synchronized void close() {
            closed = true;
            shut(stream);
        }

        synchronized boolean wasClosed() {
            return closed;
        }

        private static void shut(final @Nullable InputStream open) {
            if (open == null) {
                return;
            }
            try {
                open.close();
            } catch (IOException ignored) {
                // Closing to cancel a read; the read is what reports anything worth reporting.
            }
        }
    }
}
