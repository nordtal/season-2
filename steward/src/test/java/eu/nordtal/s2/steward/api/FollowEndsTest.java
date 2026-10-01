package eu.nordtal.s2.steward.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import eu.nordtal.s2.steward.docker.Docker;
import eu.nordtal.s2.steward.docker.DockerSocket;
import io.javalin.Javalin;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * A log follow ends quietly when its browser goes away and when steward stops.
 *
 * Neither ending throws; a dead client shows only as one warning per line, so warnings are what this counts.
 */
class FollowEndsTest {

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
    void aFollowWhoseBrowserHasGoneStopsRatherThanReadingTheRestAloudToNobody() throws Exception {
        // Every assumption before the thing it could strand: an assumption aborts rather than running `finally`.
        final DockerSocket socket = new DockerSocket();
        assumeTrue(socket.isReachable(), "no docker socket - skipping");
        final Docker docker = new Docker(socket);
        final StackApi api = StackServer.api(docker);
        boolean closedByTheTest = false;
        final Javalin server = StackServer.start(api, PORT);
        try {
            final HttpClient http = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
            final String name = aRunningService(http);

            try (WarningWatch watch = WarningWatch.attach()) {
                followThenLeaveLikeABrowser(http, name);
                Thread.sleep(200);

                api.close();
                server.stop();
                closedByTheTest = true;
                // A second is nothing next to a retry storm, and long enough for one follow ending to settle.
                Thread.sleep(1_000);

                assertTrue(
                        watch.count() < 5,
                        "a follow that lost its browser, and the shutdown after it, logged " + watch.count()
                                + " warnings. One per line of the backlog is the shape to look for:"
                                + " LogFollows asks client.terminated() before it writes, because Javalin"
                                + " will not tell it any other way");
            }
        } finally {
            // The close is what is under test, so it happens above; this only covers paths that never got there.
            if (!closedByTheTest) {
                api.close();
                server.stop();
            }
        }
    }

    /**
     * Opens the follow and leaves it the way a browser leaves.
     *
     * A first line is read so the follow is established rather than merely accepted, then the body is closed and
     * the server told nothing.
     */
    private static void followThenLeaveLikeABrowser(final HttpClient http, final String name) throws Exception {
        final HttpResponse<InputStream> follow = http.send(
                HttpRequest.newBuilder(
                                URI.create("http://127.0.0.1:" + PORT + "/api/services/" + name + "/logs?tail=200"))
                        .header("Accept", "text/event-stream")
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, follow.statusCode());

        try (InputStream lines = follow.body()) {
            assertTrue(lines.read() != -1, "the follow ended before it said anything");
        }
    }

    /** Counts warnings on the ROOT logger while attached, and detaches itself on {@link #close()}. */
    private static final class WarningWatch implements AutoCloseable {

        private final ch.qos.logback.classic.Logger root;
        private final Warnings warnings;

        private WarningWatch(final ch.qos.logback.classic.Logger root, final Warnings warnings) {
            this.root = root;
            this.warnings = warnings;
        }

        static WarningWatch attach() {
            final Warnings warnings = new Warnings();
            final ch.qos.logback.classic.Logger root =
                    (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
            warnings.setContext(root.getLoggerContext());
            warnings.start();
            root.addAppender(warnings);
            return new WarningWatch(root, warnings);
        }

        int count() {
            return warnings.count.get();
        }

        @Override
        public void close() {
            root.detachAppender(warnings);
            warnings.stop();
        }
    }

    /** A service with a container actually running, or a skip, since a stopped one never follows. */
    private static String aRunningService(final HttpClient http) throws Exception {
        final HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + PORT + "/api/services"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        for (final var row : GSON.fromJson(response.body(), JsonObject.class).getAsJsonArray("services")) {
            final JsonObject service = row.getAsJsonObject();
            if ("running".equals(service.get("state").getAsString())) {
                return service.get("service").getAsString();
            }
        }
        assumeTrue(false, "no container of the stack is running - skipping");
        throw new AssertionError("unreachable");
    }
}
