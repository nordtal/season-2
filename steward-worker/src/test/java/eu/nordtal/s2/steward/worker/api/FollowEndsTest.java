package eu.nordtal.s2.steward.worker.api;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import eu.nordtal.s2.steward.worker.docker.Console;
import eu.nordtal.s2.steward.worker.docker.Docker;
import eu.nordtal.s2.steward.worker.docker.DockerOps;
import eu.nordtal.s2.steward.worker.docker.DockerSocket;
import eu.nordtal.s2.steward.worker.host.HostMetrics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * What happens to a log follow when the browser watching it goes away, and when the worker stops.
 *
 * <h2>Why this counts warnings instead of asserting something</h2>
 * Neither ending fails anything. Javalin does not throw when a client has gone: writing to a
 * terminated {@code SseClient} logs <em>"Cannot send data"</em> and returns - so the follow read on
 * happily through the container's backlog and reported every line of it to nobody, one warning per
 * line. Measured on this host on 2026-09-13, a {@code tail=200} follow whose reader left after one
 * line still wrote 69 of them, and a chatty container has far more than 200 lines behind it.
 *
 * <p>There is no status code and no exception in any of that. The symptom <em>is</em> the logging,
 * so the logging is what this counts.</p>
 *
 * <p>The shutdown at the end is the second half, and it is held less tightly on purpose: the
 * failure there - an emitter closed against a request Jetty has already recycled, which threw,
 * which the exception mapper could not report because it threw too, which was then retried, about
 * sixty thousand times a second until the JVM ran out of memory - was measured on 2026-09-13 but
 * needs a client that is still connected at the moment Jetty stops, and a test that hangs on to a
 * socket that precisely is a test that will one day fail for its own reasons. What is asserted
 * here is that closing a worker with a follow behind it is quiet. See {@code WorkerApi#follows}
 * for the order that keeps it that way.</p>
 */
class FollowEndsTest {

    private static final String PROJECT = "nordtal-s2";
    private static final String TOKEN = "test-token-not-a-secret";
    private static final int PORT = 18084;
    private static final Gson GSON = new Gson();

    /** Counts, rather than collecting: a storm would fill a list faster than it could be read. */
    private static final class Warnings extends AppenderBase<ILoggingEvent> {

        private final AtomicInteger count = new AtomicInteger();

        @Override
        protected void append(final ILoggingEvent event) {
            if (event.getLevel().toInt() >= ch.qos.logback.classic.Level.WARN.toInt()) {
                count.incrementAndGet();
            }
        }
    }

    @Test
    @DisplayName("a follow whose browser has gone stops, rather than reading the rest aloud to nobody")
    void aFollowEndsWithItsBrowser() throws Exception {
        final Warnings warnings = new Warnings();
        final ch.qos.logback.classic.Logger root = (ch.qos.logback.classic.Logger)
                LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        warnings.setContext(root.getLoggerContext());
        warnings.start();
        root.addAppender(warnings);

        final DockerSocket socket = new DockerSocket();
        assumeTrue(socket.isReachable(), "no docker socket - skipping");
        final Docker docker = new Docker(socket);
        final WorkerApi api = new WorkerApi(docker, new DockerOps(docker, PROJECT),
                new Console(docker, PROJECT), new HostMetrics(), PROJECT, Path.of("/tmp"), TOKEN);
        api.start(PORT);

        final HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5)).build();
        final String name = aRunningService(http);

        final HttpResponse<InputStream> follow = http.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + PORT + "/api/services/" + name
                                + "/logs?tail=200"))
                .header("X-Steward-Token", TOKEN)
                .header("Accept", "text/event-stream")
                .GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, follow.statusCode());

        // Read one line, so the follow is established rather than merely accepted, and then leave
        // the way a browser leaves: the body closed and the server told nothing. That is the state
        // the defect needs - an emitter whose connection is already broken, closed a second time
        // by a shutdown - and it is also the ordinary case, because a tab closing is the usual way
        // a log view ends.
        try (InputStream lines = follow.body()) {
            assertTrue(lines.read() != -1, "the follow ended before it said anything");
        }
        Thread.sleep(200);

        try {
            api.close();
            // A second is nothing next to sixty thousand a second, and it is long enough for the
            // ordinary path - one follow ending, one emitter closing - to have happened.
            Thread.sleep(1_000);

            assertTrue(warnings.count.get() < 5,
                    "a follow that lost its browser, and the shutdown after it, logged "
                    + warnings.count.get() + " warnings. One per line of the backlog is the shape"
                    + " to look for: WorkerApi asks client.terminated() before it writes, because"
                    + " Javalin will not tell it any other way");
        } finally {
            root.detachAppender(warnings);
            warnings.stop();
        }
    }

    /** A service with a container actually running, or a skip - a stopped one never follows. */
    private static String aRunningService(final HttpClient http) throws Exception {
        final HttpResponse<String> response = http.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + PORT + "/api/services"))
                .header("X-Steward-Token", TOKEN).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        for (final var row : GSON.fromJson(response.body(), JsonObject.class)
                .getAsJsonArray("services")) {
            final JsonObject service = row.getAsJsonObject();
            if ("running".equals(service.get("state").getAsString())) {
                return service.get("service").getAsString();
            }
        }
        assumeTrue(false, "no container of the stack is running - skipping");
        throw new AssertionError("unreachable");
    }
}
