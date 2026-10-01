package eu.nordtal.s2.steward.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import eu.nordtal.s2.common.time.Waiting;
import eu.nordtal.s2.steward.ops.ContainerOps;
import eu.nordtal.s2.steward.ops.ImageResult;
import eu.nordtal.s2.steward.ops.RedeployResult;
import eu.nordtal.s2.steward.ops.RuntimeResult;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * {@link DeployerRecreate} against a hand-written stand-in for steward-deployer's HTTP API.
 *
 * Every test is bounded, since a fake clock that never reaches its deadline turns the poll into a busy loop.
 */
@Timeout(10)
class DeployerRecreateTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    private DeployerRecreate client(final ContainerOps delegate, final Duration patience) {
        return new DeployerRecreate(
                delegate,
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "a-secret",
                Duration.ofSeconds(5),
                patience,
                Waiting.on(Clock.systemUTC()));
    }

    // The happy path: accepted, then DONE

    @Test
    void aJobThatSettlesDoneIsATriggeredRecreate() throws IOException {
        final Queue<String> jobStates = new ConcurrentLinkedQueue<>(List.of("RUNNING", "DONE"));
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/deploy", exchange -> {
            assertEquals("POST", exchange.getRequestMethod());
            assertEquals("a-secret", exchange.getRequestHeaders().getFirst("X-Steward-Token"));
            respond(exchange, 202, "{\"id\":\"job-1\",\"kind\":\"recreate\",\"state\":\"RUNNING\"}");
        });
        server.createContext("/api/jobs/job-1", exchange -> {
            final String state = jobStates.size() > 1 ? jobStates.poll() : jobStates.peek();
            respond(
                    exchange,
                    200,
                    "{\"id\":\"job-1\",\"state\":\"" + state
                            + "\",\"lines\":[\"pulling smp\",\"Recreating nordtal-s2-smp-1\"]}");
        });
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();

        final RedeployResult result =
                client(new NoopDelegate(), Duration.ofSeconds(5)).deploy("smp");

        assertTrue(result.triggered(), result.message());
        assertTrue(result.verified(), result.message());
        assertTrue(result.message().contains("smp"), result.message());
    }

    // The route an update run asks, naming the one service

    @Test
    void anUpdateRunAsksTheRouteThatPullsNamingTheOneService() throws IOException {
        // /api/recreate uses the image already on this host; the stand-in below answers on both routes to prove which.
        final Queue<String> bodies = new ConcurrentLinkedQueue<>();
        final AtomicInteger recreateCalls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/deploy", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 202, "{\"id\":\"job-4\"}");
        });
        server.createContext("/api/recreate/smp", exchange -> {
            recreateCalls.incrementAndGet();
            respond(exchange, 202, "{\"id\":\"job-4\"}");
        });
        server.createContext(
                "/api/jobs/job-4",
                exchange -> respond(exchange, 200, "{\"id\":\"job-4\",\"state\":\"DONE\",\"lines\":[]}"));
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();

        final RedeployResult result =
                client(new NoopDelegate(), Duration.ofSeconds(5)).deploy("smp");

        assertTrue(result.triggered(), result.message());
        assertEquals(
                0,
                recreateCalls.get(),
                "an update run must not ask the route that uses the image already on this host");
        assertEquals(1, bodies.size(), "the deploy route was asked exactly once");
        assertEquals("{\"services\":[\"smp\"]}", bodies.peek());
    }

    @Test
    void startingAStandbyAsksTheRouteThatDoesNotPull() throws IOException {
        // A standby must come up on the image its live service runs, often a local build, so a pull here would diverge.
        final Queue<String> bodies = new ConcurrentLinkedQueue<>();
        final AtomicInteger recreateCalls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/deploy", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 202, "{\"id\":\"job-5\"}");
        });
        server.createContext("/api/recreate/limbo-standby", exchange -> {
            recreateCalls.incrementAndGet();
            respond(exchange, 202, "{\"id\":\"job-5\"}");
        });
        server.createContext(
                "/api/jobs/job-5",
                exchange -> respond(exchange, 200, "{\"id\":\"job-5\",\"state\":\"DONE\",\"lines\":[]}"));
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();

        final RedeployResult result =
                client(new NoopDelegate(), Duration.ofSeconds(5)).recreate("limbo-standby");

        assertTrue(result.triggered(), result.message());
        assertEquals(1, recreateCalls.get(), "the standby is made from the image already here");
        assertTrue(bodies.isEmpty(), "starting a standby must not fetch an image");
    }

    @Test
    void theDeployBodyNamesOneServiceNeverTheEmptyListComposeReadsAsAll() {
        assertEquals("{\"services\":[\"smp\"]}", DeployerRecreate.deployBody("smp"));
        assertTrue(
                DeployerRecreate.deployBody("a\"b").contains("a\\\"b"),
                "a service name is escaped, not concatenated into the JSON");
    }

    // The deployer refuses outright

    @Test
    void aNon202FromPostApiDeployIsRefusedNamed() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/deploy", exchange -> respond(exchange, 400, "\"smp\" is not a compose service"));
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();

        final RedeployResult result =
                client(new NoopDelegate(), Duration.ofSeconds(5)).deploy("smp");

        assertFalse(result.triggered(), result.message());
        assertTrue(result.message().contains("smp"), result.message());
        assertTrue(result.message().contains("400"), result.message());
    }

    // The job itself fails

    @Test
    void aJobThatSettlesFailedIsARefusedRecreateCarryingItsLastLine() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/deploy", exchange -> respond(exchange, 202, "{\"id\":\"job-2\"}"));
        server.createContext(
                "/api/jobs/job-2",
                exchange -> respond(
                        exchange,
                        200,
                        "{\"id\":\"job-2\",\"state\":\"FAILED\",\"lines\":[\"pulling smp\",\"no such image\"]}"));
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();

        final RedeployResult result =
                client(new NoopDelegate(), Duration.ofSeconds(5)).deploy("smp");

        assertFalse(result.triggered(), result.message());
        assertTrue(result.message().contains("no such image"), result.message());
    }

    // The deployer is not there at all

    @Test
    void noServerListeningIsRefusedNotAnExceptionReachingTheCaller() {
        // No HttpServer created or started at all: nothing is listening on this port, the "deployer is down" case.
        final DeployerRecreate client = new DeployerRecreate(
                new NoopDelegate(),
                "http://127.0.0.1:1",
                "a-secret",
                Duration.ofSeconds(1),
                Duration.ofSeconds(5),
                Waiting.on(Clock.systemUTC()));

        final RedeployResult result = client.deploy("smp");

        assertFalse(result.triggered(), result.message());
        assertTrue(result.message().contains("smp"), result.message());
    }

    // A job that never settles within this call's patience

    @Test
    void aJobStillRunningWhenPatienceRunsOutIsUnverifiedNotRefused() throws IOException {
        final AtomicInteger polls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/deploy", exchange -> respond(exchange, 202, "{\"id\":\"job-3\"}"));
        server.createContext("/api/jobs/job-3", exchange -> {
            polls.incrementAndGet();
            respond(exchange, 200, "{\"id\":\"job-3\",\"state\":\"RUNNING\",\"lines\":[]}");
        });
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();

        // The clock must advance between calls, not start ahead: a clock stuck on one instant never meets its deadline.
        final Instant t0 = Instant.now();
        final AtomicInteger calls = new AtomicInteger();
        final DeployerRecreate client = new DeployerRecreate(
                new NoopDelegate(),
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "a-secret",
                Duration.ofSeconds(5),
                Duration.ofSeconds(5),
                new Waiting() {
                    @Override
                    public Instant now() {
                        // First call (recreate()) sets t0; every poll() after is ten seconds past its patience.
                        return calls.getAndIncrement() == 0 ? t0 : t0.plus(Duration.ofSeconds(10));
                    }

                    @Override
                    public boolean sleep(final Duration duration) {
                        return true;
                    }
                });

        final RedeployResult result = client.deploy("smp");

        assertTrue(result.triggered(), result.message());
        assertFalse(result.verified(), result.message());
        assertTrue(result.message().contains("job-3"), result.message());
        assertEquals(1, polls.get());
    }

    private static void respond(final com.sun.net.httpserver.HttpExchange exchange, final int status, final String body)
            throws IOException {
        final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** Every other {@link ContainerOps} call, unused by these tests and never asked. */
    private static final class NoopDelegate implements ContainerOps {
        @Override
        public RuntimeResult runtime() {
            throw new UnsupportedOperationException();
        }

        @Override
        public RedeployResult stop(final String containerId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RedeployResult start(final String containerId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ImageResult images() {
            throw new UnsupportedOperationException();
        }

        @Override
        public RedeployResult deploy(final String service) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RedeployResult recreate(final String service) {
            throw new UnsupportedOperationException();
        }
    }
}
