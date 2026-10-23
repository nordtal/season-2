package eu.nordtal.season.steward.stack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import eu.nordtal.season.stewardagent.AgentStandIn;
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

/** Tests that a log follow ends without an exception when its browser leaves and when Steward stops. */
class FollowEndsTest {

    private static final Gson GSON = new Gson();

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
        final java.nio.file.Path scratch =
                java.nio.file.Files.createTempDirectory(java.nio.file.Path.of("/tmp"), "ends");
        final AgentStandIn agent = new AgentStandIn(scratch, 0, config -> {});
        final StackApi api = StackServer.api(agent);
        boolean closedByTheTest = false;
        // Any free port, so two builds on one host never collide.
        final Javalin server = StackServer.start(api, 0);
        try {
            final HttpClient http = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
            final String name = aRunningService(http, server.port());

            try (WarningWatch watch = WarningWatch.attach()) {
                followThenLeaveLikeABrowser(http, server.port(), name);
                Thread.sleep(200);

                api.close();
                server.stop();
                closedByTheTest = true;
                Thread.sleep(1_000);

                assertTrue(
                        watch.count() < 5,
                        "a follow that lost its browser, and the shutdown after it, logged " + watch.count()
                                + " warnings. One per line of the backlog is the shape to look for:"
                                + " Follows asks client.terminated() before it writes, because Javalin"
                                + " will not tell it any other way");
            }
        } finally {
            if (!closedByTheTest) {
                api.close();
                server.stop();
            }
            agent.close();
            try (var files = java.nio.file.Files.walk(scratch)) {
                files.sorted(java.util.Comparator.reverseOrder())
                        .forEach(path -> path.toFile().delete());
            }
        }
    }

    /** Opens the follow, reads the first line and closes the body. */
    private static void followThenLeaveLikeABrowser(final HttpClient http, final int port, final String name)
            throws Exception {
        final HttpResponse<InputStream> follow = http.send(
                HttpRequest.newBuilder(
                                URI.create("http://127.0.0.1:" + port + "/api/services/" + name + "/logs?tail=200"))
                        .header("Accept", "text/event-stream")
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, follow.statusCode());

        try (InputStream lines = follow.body()) {
            assertTrue(lines.read() != -1, "the follow ended before it said anything");
        }
    }

    /** Counts warnings on the ROOT logger until {@link #close()}. */
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

    /** Returns a service with a running container, or skips the test. */
    private static String aRunningService(final HttpClient http, final int port) throws Exception {
        final HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/services"))
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
