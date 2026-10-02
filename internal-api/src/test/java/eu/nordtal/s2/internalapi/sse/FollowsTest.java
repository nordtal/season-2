package eu.nordtal.s2.internalapi.sse;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.javalin.Javalin;
import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The one way a long event stream is served: it ends when its reader is no longer wanted, and before Jetty stops. */
class FollowsTest {

    private final Follows follows = new Follows("test-process");
    private final AtomicBoolean wanted = new AtomicBoolean(true);
    private final AtomicBoolean sourceClosed = new AtomicBoolean();
    private Javalin server;

    @AfterEach
    void stop() {
        follows.close();
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void aReaderNoLongerWantedIsToldWhyAndTheSourceCloses() throws Exception {
        start();
        final List<String> lines = new ArrayList<>();
        try (BufferedReader body = open()) {
            String line;
            while ((line = body.readLine()) != null) {
                lines.add(line);
                if (line.equals("data: tick")) {
                    wanted.set(false);
                }
            }
        }
        assertTrue(lines.contains("event: gone"), "expected a gone event: " + lines);
        assertTrue(lines.contains("data: signed out"), "expected the sentence: " + lines);
        assertTrue(sourceClosed.get(), "the source was left open");
    }

    @Test
    void closingTheProcessEndsAnOpenFollowAndItsSource() throws Exception {
        start();
        try (BufferedReader body = open()) {
            assertTrue(body.readLine() != null, "the follow never started");
            follows.close();
            final long giveUp = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (body.readLine() != null && System.nanoTime() < giveUp) {
                // Drains until the stream ends.
            }
        }
        assertTrue(sourceClosed.get(), "the source was left open at shutdown");
    }

    private void start() {
        final Closeable source = () -> sourceClosed.set(true);
        server = Javalin.create(config -> config.routes.sse(
                        "/follow",
                        client -> follows.serve(
                                client, "ticks", source, new Follows.Wanted(wanted::get, "signed out"), sink -> {
                                    while (!sourceClosed.get()) {
                                        sink.send("line", "tick");
                                        try {
                                            Thread.sleep(200);
                                        } catch (final InterruptedException e) {
                                            return;
                                        }
                                    }
                                })))
                .start("127.0.0.1", 0);
    }

    private BufferedReader open() throws IOException, InterruptedException {
        final HttpResponse<InputStream> response = HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/follow"))
                                .header("Accept", "text/event-stream")
                                .timeout(Duration.ofSeconds(10))
                                .build(),
                        HttpResponse.BodyHandlers.ofInputStream());
        return new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8));
    }
}
