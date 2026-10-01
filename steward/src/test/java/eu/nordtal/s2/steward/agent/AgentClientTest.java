package eu.nordtal.s2.steward.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * The two decisions {@link AgentClient} makes before and after the wire.
 *
 * A real socket shows the JDK client, built as this class builds it, hands a {@code 307} back rather than following it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AgentClientTest {

    private static HttpServer server;
    private static AgentClient client;

    @BeforeAll
    static void startAServerThatAnswersWhateverIsAskedOfIt() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        // The path is the status to answer with, plus one endpoint that takes longer than any timeout.
        server.createContext("/api/slow", exchange -> {
            try {
                Thread.sleep(Duration.ofSeconds(5));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.createContext("/api/", exchange -> {
            final int status =
                    Integer.parseInt(exchange.getRequestURI().getPath().substring("/api/".length()));
            final byte[] body = ("body of " + status).getBytes(StandardCharsets.UTF_8);
            if (status == 204 || status == 307 || status == 304) {
                // A status that carries no body: 204 by definition, and a redirect because a proxy rarely bothers.
                exchange.getResponseHeaders().add("Location", "http://elsewhere.example.com/");
                exchange.sendResponseHeaders(status, -1);
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        // A thread per exchange, so the slow handler does not stall the rest on the default executor.
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        client =
                new AgentClient("http://127.0.0.1:" + server.getAddress().getPort(), "a-secret", Duration.ofSeconds(5));
    }

    @AfterAll
    static void stopIt() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    @Test
    void aTokenNeverLeavesTheHostInClear() {
        // base-url is editable from the interface, so this refuses in the constructor rather than leaking later.
        for (final String outside : new String[] {
            "http://steward.nordtal.eu",
            "http://45.155.173.214",
            "http://agent.internal:8081",
            "http://127.0.0.1.nip.io:8081"
        }) {
            final IllegalArgumentException refused = assertThrows(
                    IllegalArgumentException.class,
                    () -> new AgentClient(outside, "a-secret", Duration.ofSeconds(1)),
                    outside);
            assertTrue(
                    refused.getMessage().contains("steward-agent")
                            && refused.getMessage().contains(outside),
                    "the message has to name the service and the address somebody typed, because"
                            + " the person reading it is looking at a config form: "
                            + refused.getMessage());
        }
    }

    @Test
    void theThreeThatAreAllowed() {
        // A dotless host is a compose service on the internal network; loopback is the other allowed case.
        for (final String inside : new String[] {
            "https://steward.nordtal.eu",
            "HTTPS://steward.nordtal.eu",
            "http://steward:8081",
            "http://127.0.0.1:8081",
            "http://localhost:8081",
            "http://[::1]:8081",
            "http://steward:8081/"
        }) {
            new AgentClient(inside, "a-secret", Duration.ofSeconds(1));
        }
    }

    @Test
    void nothingToProtectMeansNothingToRefuse() {
        // The unconfigured agent already refuses everything through AgentApi#require.
        new AgentClient("http://steward.nordtal.eu", "", Duration.ofSeconds(1));
        new AgentClient("http://anything.example.com", "   ", Duration.ofSeconds(1));
    }

    @Test
    void aHalfWrittenAddressIsNotHalfAccepted() {
        // A forgotten scheme parses as scheme `steward` with no host, which must land in the refusal.
        assertThrows(
                IllegalArgumentException.class,
                () -> new AgentClient("steward:8081", "a-secret", Duration.ofSeconds(1)));
    }

    @Test
    void aRedirectIsNotASuccess() {
        // A client that followed redirects would answer 200 or hang here.
        final AgentClient.Failure refused = assertThrows(AgentClient.Failure.class, () -> client.get("/api/307"));

        assertEquals(307, refused.status());
        assertEquals(
                "steward-agent",
                refused.where(),
                "the interface shows which service failed, so it must not be a guess");
        assertTrue(refused.getMessage().contains("307"), refused.getMessage());

        // The same status through post(); what the message says is a separate matter, deliberately not asserted.
        assertEquals(
                307,
                assertThrows(AgentClient.Failure.class, () -> client.post("/api/307", "{}"))
                        .status());
    }

    @Test
    void noContentIsOnTheSuccessSide() {
        // 204 is what a JSON API answers a delete with nothing to say; treating it as a failure would be wrong.
        assertEquals("", client.get("/api/204"));
        assertEquals("", client.post("/api/204", "{}"));

        // Callers pass this straight into `ctx.contentType("application/json").result(answer)`, unmodified.
        assertEquals("", client.get("/api/204"));
    }

    @Test
    void theOrdinaryStatusesAreWhereTheyShouldBe() {
        assertEquals("body of 200", client.get("/api/200"));
        assertEquals("body of 201", client.post("/api/201", "{}"));

        final AgentClient.Failure refused = assertThrows(AgentClient.Failure.class, () -> client.get("/api/400"));
        assertEquals(400, refused.status());
        assertEquals(
                "body of 400",
                refused.body(),
                "the other service's own explanation is what the page has to show - without it the"
                        + " operator gets a number and a shrug");

        assertEquals(
                500,
                assertThrows(AgentClient.Failure.class, () -> client.get("/api/500"))
                        .status());
        assertEquals(
                404,
                assertThrows(AgentClient.Failure.class, () -> client.post("/api/404", "{}"))
                        .status());
    }

    @Test
    void anUnreachableServiceIsASentence() {
        // Unreachable has to be distinguishable from "answered something I did not like": different problems.
        final AgentClient nobody = new AgentClient("http://127.0.0.1:1", "a-secret", Duration.ofMillis(500));

        final AgentClient.Failure failure = assertThrows(AgentClient.Failure.class, () -> nobody.get("/api/health"));
        assertEquals(502, failure.status());
        assertEquals("steward-agent", failure.where());
        assertTrue(failure.getMessage().contains("steward-agent"), failure.getMessage());
    }

    @Test
    void aSlowServiceIsNotAnAbsentOne() {
        // HttpTimeoutException is an IOException, so the two must be caught in an order that tells them apart.
        final AgentClient patient = new AgentClient(
                "http://127.0.0.1:" + server.getAddress().getPort(), "a-secret", Duration.ofMillis(300));

        final AgentClient.Failure failure = assertThrows(AgentClient.Failure.class, () -> patient.get("/api/slow"));
        assertEquals(504, failure.status());
        assertEquals("steward-agent", failure.where());
        assertTrue(
                failure.getMessage().contains("/api/slow"),
                "the log line has to name the request, or the next session measures it again: " + failure.getMessage());
        assertTrue(failure.getMessage().contains("did not answer"), failure.getMessage());
    }

    @Test
    void theSentenceNamesThePath() {
        final AgentClient nobody = new AgentClient("http://127.0.0.1:1", "a-secret", Duration.ofMillis(500));

        final AgentClient.Failure failure = assertThrows(AgentClient.Failure.class, () -> nobody.get("/api/services"));
        assertTrue(failure.getMessage().contains("/api/services"), failure.getMessage());
    }
}
