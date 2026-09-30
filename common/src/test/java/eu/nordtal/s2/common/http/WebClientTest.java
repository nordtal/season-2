package eu.nordtal.s2.common.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import eu.nordtal.s2.common.time.Backoff;
import eu.nordtal.s2.common.time.Waiting;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WebClientTest {

    private HttpServer server;
    private final AtomicReference<String> seenAuthorization = new AtomicReference<>();
    private final AtomicReference<String> seenBody = new AtomicReference<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/ok", exchange -> {
            seenAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            seenBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            answer(exchange, 200, "{\"name\":\"smp\"}");
        });
        server.createContext("/missing", exchange -> answer(exchange, 404, "no such thing"));
        server.createContext("/moved", exchange -> {
            exchange.getResponseHeaders().add("Location", "/ok");
            answer(exchange, 302, "");
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static void answer(final com.sun.net.httpserver.HttpExchange exchange, final int status, final String body)
            throws IOException {
        final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private URI at(final String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    record Named(String name) {}

    @Test
    void theTokenTravelsWithEveryRequestAndTheBodyReadsAsJson() throws IOException {
        final WebClient client = WebClient.create(Duration.ofSeconds(5)).bearer("secret");

        assertEquals(new Named("smp"), client.get(at("/ok")).okJson(Named.class));
        assertEquals("Bearer secret", seenAuthorization.get());
    }

    @Test
    void aStatusThatIsNotSuccessIsRefusedWithItsStatusAndBody() throws IOException {
        final WebClient client = WebClient.create(Duration.ofSeconds(5));

        final HttpFailure failure = assertThrows(HttpFailure.class, () -> client.text(at("/missing")));

        assertEquals(404, failure.status());
        assertEquals("no such thing", failure.body());
    }

    @Test
    void aRedirectIsFollowedOnlyWhenAskedFor() throws IOException {
        assertEquals(
                302, WebClient.create(Duration.ofSeconds(5)).get(at("/moved")).status());
        assertEquals(
                200,
                WebClient.create(Duration.ofSeconds(5))
                        .followingRedirects()
                        .get(at("/moved"))
                        .status());
    }

    @Test
    void aFormIsEncodedInOrder() throws IOException {
        WebClient.create(Duration.ofSeconds(5)).postForm(at("/ok"), Map.of("code", "a b&c"));

        assertEquals("code=a+b%26c", seenBody.get());
    }

    @Test
    void aDownloadLandsInItsDirectory(@TempDir final Path root) throws IOException {
        final Path file = root.resolve("plugins/smp.jar");

        WebClient.create(Duration.ofSeconds(5)).download(at("/ok"), file);

        assertEquals("{\"name\":\"smp\"}", Files.readString(file));
    }

    @Test
    void aGetThatCannotConnectIsTriedAgainAsOftenAsAsked() throws IOException {
        final int closed;
        try (ServerSocket socket = new ServerSocket(0)) {
            closed = socket.getLocalPort();
        }
        final List<Duration> pauses = new ArrayList<>();
        final Waiting counted = new Waiting() {
            @Override
            public Instant now() {
                return Instant.EPOCH;
            }

            @Override
            public boolean sleep(final Duration duration) {
                pauses.add(duration);
                return true;
            }
        };
        final WebClient client = WebClient.create(Duration.ofSeconds(2))
                .retrying(3, new Backoff(Duration.ofMillis(10), Duration.ofMillis(40)), counted);

        assertThrows(IOException.class, () -> client.get(URI.create("http://127.0.0.1:" + closed + "/")));

        assertEquals(List.of(Duration.ofMillis(10), Duration.ofMillis(20)), pauses);
    }

    @Test
    void aStatusIsAnAnswerAndIsNotRetried() throws IOException {
        final List<Duration> pauses = new ArrayList<>();
        final Waiting counted = new Waiting() {
            @Override
            public Instant now() {
                return Instant.EPOCH;
            }

            @Override
            public boolean sleep(final Duration duration) {
                pauses.add(duration);
                return true;
            }
        };
        final WebClient client =
                WebClient.create(Duration.ofSeconds(5)).retrying(3, Backoff.fixed(Duration.ofMillis(10)), counted);

        assertFalse(client.get(at("/missing")).ok());
        assertTrue(pauses.isEmpty());
    }
}
