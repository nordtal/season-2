package eu.nordtal.s2.steward.worker.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import eu.nordtal.s2.steward.worker.docker.Console;
import eu.nordtal.s2.steward.worker.docker.Docker;
import eu.nordtal.s2.steward.worker.docker.DockerOps;
import eu.nordtal.s2.steward.worker.docker.DockerSocket;
import eu.nordtal.s2.steward.worker.host.HostMetrics;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * What happens to a log follow when the browser watching it goes away, and when the worker stops.
 *
 * Why this counts warnings instead of asserting something: neither ending fails anything. Javalin does not throw
 * when a client has gone: writing to a terminated {@code SseClient} logs "Cannot send data" and returns - so the
 * follow reads on happily through the container's backlog and reports every line of it to nobody, one warning per
 * line. A chatty container has far more than the {@code tail=200} this test asks for.
 *
 * There is no status code and no exception in any of that. The symptom is the logging, so the logging is what this
 * counts.
 *
 * The shutdown at the end is the second half, and it is held less tightly on purpose: the failure there - an emitter
 * closed against a request Jetty has already recycled, which throws, which the exception mapper cannot report
 * because it throws too, retried thousands of times a second until the JVM runs out of memory - needs a client that
 * is still connected at the moment Jetty stops, and a test that hangs on to a socket that precisely is a test that
 * will one day fail for its own reasons. What is asserted here is that closing a worker with a follow behind it is
 * quiet. See {@code WorkerApi#follows} for the order that keeps it that way.
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
    void aFollowWhoseBrowserHasGoneStopsRatherThanReadingTheRestAloudToNobody() throws Exception {
        // Every assumption before the thing it could strand: an assumption aborts rather than running `finally`.
        final DockerSocket socket = new DockerSocket();
        assumeTrue(socket.isReachable(), "no docker socket - skipping");
        final Docker docker = new Docker(socket);
        final WorkerApi api = newApi(docker);
        boolean closedByTheTest = false;
        api.start(PORT);
        try {
            final HttpClient http = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
            final String name = aRunningService(http);

            try (WarningWatch watch = WarningWatch.attach()) {
                followThenLeaveLikeABrowser(http, name);
                Thread.sleep(200);

                api.close();
                closedByTheTest = true;
                // A second is nothing next to a retry storm, and long enough for one follow ending to settle.
                Thread.sleep(1_000);

                assertTrue(
                        watch.count() < 5,
                        "a follow that lost its browser, and the shutdown after it, logged " + watch.count()
                                + " warnings. One per line of the backlog is the shape to look for:"
                                + " WorkerApi asks client.terminated() before it writes, because Javalin"
                                + " will not tell it any other way");
            }
        } finally {
            // The close IS the thing under test, so it happens above; this is only for the paths that never got there.
            if (!closedByTheTest) {
                api.close();
            }
        }
    }

    /** The {@link WorkerApi} under test, wired to the real Docker daemon and a nightly window that never fires. */
    private static WorkerApi newApi(final Docker docker) {
        return new WorkerApi(
                docker,
                new DockerOps(docker, PROJECT),
                new Console(docker, PROJECT),
                new HostMetrics(),
                PROJECT,
                Path.of("/tmp"),
                TOKEN,
                Path.of("/tmp"),
                FakeDirectories.updates(),
                FakeDirectories.audit(),
                new WorkerApi.Nightly(
                        "04:45",
                        List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"),
                        ZoneId.of("Europe/Berlin")));
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
                        .header("X-Steward-Token", TOKEN)
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

    /** A service with a container actually running, or a skip - a stopped one never follows. */
    private static String aRunningService(final HttpClient http) throws Exception {
        final HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + PORT + "/api/services"))
                        .header("X-Steward-Token", TOKEN)
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
