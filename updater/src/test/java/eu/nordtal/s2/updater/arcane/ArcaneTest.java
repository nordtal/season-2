package eu.nordtal.s2.updater.arcane;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import eu.nordtal.s2.updater.config.UpdaterSpec;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The redeploy call, against a throwaway HTTP server on loopback.
 * <p>
 * There is no recorded payload to test against here, unlike every other source this module talks
 * to: Arcane's redeploy endpoint has never been called from this repository and its path is a
 * documented guess. So what is tested is the half that is ours - the URL that gets built, the
 * header that gets sent, and above all <b>what each answer is reported as</b>, because the 404 case
 * is the one that will actually happen first and the sentence it produces is the whole value.
 * </p>
 */
class ArcaneTest {

    private HttpServer server;
    private final List<String> receivedKeys = new ArrayList<>();
    private final List<String> receivedPaths = new ArrayList<>();
    private final List<String> receivedMethods = new ArrayList<>();

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    // ---------------------------------------------------------------- not configured

    @Test
    @DisplayName("an empty base-url is a supported state, not a broken one")
    void anUnconfiguredArcaneRefusesWithInstructionsRatherThanFailing() {
        final Arcane arcane = new Arcane(config("", "", "nordtal-s2", "/api/x/{project}/redeploy"));

        assertFalse(arcane.configured());

        final RedeployResult result = arcane.redeploy();
        assertFalse(result.triggered());
        assertTrue(result.message().contains("arcane.base-url is empty"), result.message());
        assertTrue(result.message().contains("click Redeploy"),
                "it says what to do instead: " + result.message());
    }

    // ---------------------------------------------------------------- the URL

    @Test
    void theProjectIsSubstitutedIntoThePath() {
        final Arcane arcane = new Arcane(
                config("https://arcane.example.com", "k", "nordtal-s2", "/api/projects/{project}/redeploy"));

        assertEquals("https://arcane.example.com/api/projects/nordtal-s2/redeploy", arcane.endpoint());
    }

    // ---------------------------------------------------------------- the call

    @Test
    void anAcceptedRedeployIsPostedWithTheApiKeyHeader() throws IOException {
        final int port = serve(202, "{\"status\":\"started\"}\n");
        final Arcane arcane = new Arcane(
                config("http://127.0.0.1:" + port, "secret-token", "nordtal-s2",
                        "/api/projects/{project}/redeploy"));

        final RedeployResult result = arcane.redeploy();

        assertTrue(result.triggered(), result.message());
        assertTrue(result.message().contains("202"), result.message());
        assertEquals(List.of("POST"), receivedMethods);
        assertEquals(List.of("/api/projects/nordtal-s2/redeploy"), receivedPaths);
        assertEquals(List.of("secret-token"), receivedKeys,
                "the token goes in X-Api-Key, which is what Arcane's documentation names");
    }

    @Test
    @DisplayName("a 404 says out loud that the path is a guess")
    void aNotFoundExplainsWhereTheRealPathComesFrom() throws IOException {
        final int port = serve(404, "not found");
        final Arcane arcane = new Arcane(
                config("http://127.0.0.1:" + port, "k", "nordtal-s2", "/api/projects/{project}/redeploy"));

        final RedeployResult result = arcane.redeploy();

        assertFalse(result.triggered());
        assertTrue(result.message().contains("THIS PATH IS A GUESS"), result.message());
        assertTrue(result.message().contains("/api/docs"),
                "and it names where the real one is: " + result.message());
    }

    @Test
    void aRefusedTokenPointsAtTheSettingThatHoldsIt() throws IOException {
        final int port = serve(401, "unauthorized");
        final Arcane arcane = new Arcane(
                config("http://127.0.0.1:" + port, "wrong", "nordtal-s2", "/r/{project}"));

        final RedeployResult result = arcane.redeploy();

        assertFalse(result.triggered());
        assertTrue(result.message().contains("arcane.api-key"), result.message());
    }

    @Test
    void anUnreachableArcaneIsReportedAndNotThrown() {
        // Port 1 on loopback: nothing listens, and the connection is refused immediately.
        final Arcane arcane = new Arcane(config("http://127.0.0.1:1", "k", "p", "/{project}"));

        final RedeployResult result = arcane.redeploy();

        assertFalse(result.triggered());
        assertTrue(result.message().startsWith("Could not reach Arcane"), result.message());
    }

    @Test
    @DisplayName("an unexpected status still produces one readable sentence")
    void anythingElseIsReportedWithItsStatus() throws IOException {
        final int port = serve(503, "");
        final Arcane arcane = new Arcane(config("http://127.0.0.1:" + port, "k", "p", "/{project}"));

        final RedeployResult result = arcane.redeploy();

        assertFalse(result.triggered());
        assertTrue(result.message().contains("HTTP 503"), result.message());
        assertTrue(result.message().contains("Nothing was restarted"), result.message());
    }

    // ---------------------------------------------------------------- helpers

    private int serve(final int status, final String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final AtomicInteger unused = new AtomicInteger();
        server.createContext("/", (final HttpExchange exchange) -> {
            unused.incrementAndGet();
            receivedMethods.add(exchange.getRequestMethod());
            receivedPaths.add(exchange.getRequestURI().getPath());
            final String key = exchange.getRequestHeaders().getFirst("X-Api-Key");
            if (key != null) {
                receivedKeys.add(key);
            }
            final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.start();
        return server.getAddress().getPort();
    }

    private static UpdaterSpec.ArcaneSpec config(final String baseUrl, final String apiKey,
                                                 final String project, final String path) {
        return new UpdaterSpec.ArcaneSpec() {
            @Override
            public String baseUrl() {
                return baseUrl;
            }

            @Override
            public String apiKey() {
                return apiKey;
            }

            @Override
            public String project() {
                return project;
            }

            @Override
            public String redeployPath() {
                return path;
            }

            @Override
            public int timeoutSeconds() {
                return 5;
            }
        };
    }
}
