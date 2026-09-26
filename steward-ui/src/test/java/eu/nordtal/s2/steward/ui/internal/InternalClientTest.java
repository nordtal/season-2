package eu.nordtal.s2.steward.ui.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * The two decisions {@link InternalClient} makes before and after the wire.
 *
 * <h2>Why a real socket for the second half</h2>
 * {@code isNotSuccess} is private, and testing it directly would be testing an inequality. What is
 * actually being asserted is that the JDK's client, built the way this class builds it, hands a
 * {@code 307} back to be refused rather than following it - and "the JDK's default redirect policy
 * is NEVER" is exactly the kind of fact that is true until somebody adds a
 * {@code followRedirects(ALWAYS)} to the builder for an unrelated reason. Only a server that
 * actually answers 307 can tell those two worlds apart.
 *
 * <p>The failure that produced the current boundary was real: it was {@code >= 400}, a proxy in
 * front of the deployer answered {@code 307}, and {@code DeployerApi.recreate} reported
 * {@code 202 Accepted} for a job nothing had accepted.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InternalClientTest {

    private static HttpServer server;
    private static InternalClient client;

    @BeforeAll
    static void startAServerThatAnswersWhateverIsAskedOfIt() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // The path is the status to answer with, so one handler covers every case below.
        // One endpoint that takes longer than anybody's timeout. It is the drift refresh of
        // 2026-09-14 in miniature: an answer that is coming, only not yet.
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
                // A status that carries no body, which is the point of these three: 204 by
                // definition, and a redirect because a proxy emitting one rarely bothers.
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
        // A thread per exchange, because one handler here sleeps: the default executor runs every
        // request on the dispatcher thread, so the slow one would stall the other seven tests.
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        client = new InternalClient(
                "steward-deployer",
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "a-secret",
                Duration.ofSeconds(5));
    }

    @AfterAll
    static void stopIt() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    // -------------------------------------------------------------------------------------------
    // Where a token in clear may go
    // -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a plaintext base address outside this deployment is refused while it is still configuration")
    void aTokenNeverLeavesTheHostInClear() {
        // base-url is a setting and it is editable from the interface itself, so this is one
        // careless save away from putting the deployer's token - the one credential in this stack
        // that may create containers - on the open internet, with nothing anywhere saying so. The
        // refusal is in the constructor, which means the container does not start rather than
        // starting and leaking.
        for (final String outside : new String[] {
            "http://steward.nordtal.eu",
            "http://45.155.173.214",
            "http://worker.internal:8081",
            "http://127.0.0.1.nip.io:8081"
        }) {
            final IllegalArgumentException refused = assertThrows(
                    IllegalArgumentException.class,
                    () -> new InternalClient("steward-deployer", outside, "a-secret", Duration.ofSeconds(1)),
                    outside);
            assertTrue(
                    refused.getMessage().contains("steward-deployer")
                            && refused.getMessage().contains(outside),
                    "the message has to name the service and the address somebody typed, because"
                            + " the person reading it is looking at a config form: "
                            + refused.getMessage());
        }
    }

    @Test
    @DisplayName("https anywhere, and plain http to a compose name or to loopback, are accepted")
    void theThreeThatAreAllowed() {
        // A dotless host cannot be a public DNS name, so it is a compose service on the internal
        // network - which is where the default `http://steward-worker:8081` points and is the whole
        // ordinary case. Loopback is the other one: the test stand-in for a service, on this host.
        for (final String inside : new String[] {
            "https://steward.nordtal.eu",
            "HTTPS://steward.nordtal.eu",
            "http://steward-worker:8081",
            "http://127.0.0.1:8081",
            "http://localhost:8081",
            "http://[::1]:8081",
            "http://steward-worker:8081/"
        }) {
            new InternalClient("steward-deployer", inside, "a-secret", Duration.ofSeconds(1));
        }
    }

    @Test
    @DisplayName("a client with no token configured is not checked, because it sends no secret")
    void nothingToProtectMeansNothingToRefuse() {
        // The unconfigured deployer. It refuses to do anything anyway - DeployerApi#require answers
        // a sentence about the setup script - and refusing it in the constructor as well would turn
        // "the deployer is not set up yet" into a container that will not start.
        new InternalClient("steward-deployer", "http://steward.nordtal.eu", "", Duration.ofSeconds(1));
        new InternalClient("steward-deployer", "http://anything.example.com", "   ", Duration.ofSeconds(1));
    }

    @Test
    @DisplayName("an address with no scheme at all is refused rather than guessed at")
    void aHalfWrittenAddressIsNotHalfAccepted() {
        // `steward-worker:8081` - the value somebody types when they mean the default and forget
        // the scheme. URI parses it as scheme `steward-worker` with no host, which is neither https
        // nor http-to-something-inside, so it lands in the refusal. Worth pinning: a host==null
        // branch that fell through to "allow" would accept exactly the typo most likely to be made.
        assertThrows(
                IllegalArgumentException.class,
                () -> new InternalClient("steward-worker", "steward-worker:8081", "a-secret", Duration.ofSeconds(1)));
    }

    // -------------------------------------------------------------------------------------------
    // Which side of the boundary a status is on
    // -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a 307 is a failure and is not followed, which is the bug this boundary was moved for")
    void aRedirectIsNotASuccess() {
        // If the client ever started following redirects, this would answer 200 from
        // elsewhere.example.com - or, with no network, hang. Either is louder than what the old
        // `>= 400` did, which was to report 202 Accepted for a recreate nothing had accepted.
        final InternalClient.Failure refused = assertThrows(InternalClient.Failure.class, () -> client.get("/api/307"));

        assertEquals(307, refused.status());
        assertEquals(
                "steward-deployer",
                refused.where(),
                "the interface shows which of the two services failed, so it must not be a guess");
        assertTrue(refused.getMessage().contains("307"), refused.getMessage());

        // The same status through post(). The status and the service are right here too; what the
        // message says is a separate matter and is deliberately not asserted - see the report that
        // came with this test.
        assertEquals(
                307,
                assertThrows(InternalClient.Failure.class, () -> client.post("/api/307", "{}"))
                        .status());
    }

    @Test
    @DisplayName("a 204 is a success, and what it hands back is an empty body rather than an error")
    void noContentIsOnTheSuccessSide() {
        // The other edge of the same window. 204 is the answer a JSON API gives to a delete or an
        // accepted command with nothing to say, and treating it as a failure would turn a
        // successful action into a red box. Nothing behind this interface answers 204 today, which
        // is exactly why the behaviour is worth writing down before something starts to.
        assertEquals("", client.get("/api/204"));
        assertEquals("", client.post("/api/204", "{}"));

        // What the callers then do with it is worth knowing: DeployerApi and WorkerApi pass this
        // straight into `ctx.contentType("application/json").result(answer)`, so a 204 from either
        // service reaches the browser as a 200 with an empty body and a JSON content type - which
        // `response.json()` rejects. Asserted here as the fact it is, not as an approval of it.
        assertEquals("", client.get("/api/204"));
    }

    @Test
    @DisplayName("200 is a success and 400 and 500 are not, and the body rides along for the page")
    void theOrdinaryStatusesAreWhereTheyShouldBe() {
        assertEquals("body of 200", client.get("/api/200"));
        assertEquals("body of 201", client.post("/api/201", "{}"));

        final InternalClient.Failure refused = assertThrows(InternalClient.Failure.class, () -> client.get("/api/400"));
        assertEquals(400, refused.status());
        assertEquals(
                "body of 400",
                refused.body(),
                "the other service's own explanation is what the page has to show - without it the"
                        + " operator gets a number and a shrug");

        assertEquals(
                500,
                assertThrows(InternalClient.Failure.class, () -> client.get("/api/500"))
                        .status());
        assertEquals(
                404,
                assertThrows(InternalClient.Failure.class, () -> client.post("/api/404", "{}"))
                        .status());
    }

    @Test
    @DisplayName("a service that is not listening is a 502 that names it, not a stack trace")
    void anUnreachableServiceIsASentence() {
        // The evening half of this stack is down. It has to be distinguishable from "the service
        // answered something I did not like", because the two are different problems and the page
        // shows one line either way.
        final InternalClient nobody =
                new InternalClient("steward-worker", "http://127.0.0.1:1", "a-secret", Duration.ofMillis(500));

        final InternalClient.Failure failure =
                assertThrows(InternalClient.Failure.class, () -> nobody.get("/api/health"));
        assertEquals(502, failure.status());
        assertEquals("steward-worker", failure.where());
        assertTrue(failure.getMessage().contains("steward-worker"), failure.getMessage());
    }

    @Test
    @DisplayName("a service that answers too slowly is a 504 that says so, not 'could not be reached'")
    void aSlowServiceIsNotAnAbsentOne() {
        // MEASURED on the dev host, 2026-09-14. `GET /api/services` on steward-worker takes 11.5 s
        // whenever its one-minute drift cache has expired, against a 10 s timeout here. Every
        // IOException was one sentence, so the log said "steward-worker could not be reached at
        // http://steward-worker:8082" about a service that was healthy, listening and answering -
        // once a minute, for hours. An operator reading that goes looking at the network.
        //
        // HttpTimeoutException IS an IOException, which is why the two have to be caught in this
        // order and why the wrong sentence was so easy to write.
        final InternalClient patient = new InternalClient(
                "steward-worker",
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "a-secret",
                Duration.ofMillis(300));

        final InternalClient.Failure failure =
                assertThrows(InternalClient.Failure.class, () -> patient.get("/api/slow"));
        assertEquals(504, failure.status());
        assertEquals("steward-worker", failure.where());
        assertTrue(
                failure.getMessage().contains("/api/slow"),
                "the log line has to name the request, or the next session measures it again: " + failure.getMessage());
        assertTrue(failure.getMessage().contains("did not answer"), failure.getMessage());
    }

    @Test
    @DisplayName("an unreachable service names the request too")
    void theSentenceNamesThePath() {
        final InternalClient nobody =
                new InternalClient("steward-worker", "http://127.0.0.1:1", "a-secret", Duration.ofMillis(500));

        final InternalClient.Failure failure =
                assertThrows(InternalClient.Failure.class, () -> nobody.get("/api/services"));
        assertTrue(failure.getMessage().contains("/api/services"), failure.getMessage());
    }
}
