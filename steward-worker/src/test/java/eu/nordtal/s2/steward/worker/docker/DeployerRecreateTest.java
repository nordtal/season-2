package eu.nordtal.s2.steward.worker.docker;

import com.sun.net.httpserver.HttpServer;
import eu.nordtal.s2.steward.worker.ops.ContainerOps;
import eu.nordtal.s2.steward.worker.ops.ImageResult;
import eu.nordtal.s2.steward.worker.ops.RedeployResult;
import eu.nordtal.s2.steward.worker.ops.RuntimeResult;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DeployerRecreate} against a hand-written stand-in for steward-deployer's HTTP API.
 *
 * <h2>What this replaces</h2>
 * Before season-2-ops/22, {@code DockerOps#recreate} refused unconditionally - "recreating smp is
 * steward-deployer's ... Not wired from here yet". Every test below constructs the object this
 * ticket adds and checks it against the same three answers {@link RedeployResult} always
 * distinguished: {@link RedeployResult#triggered}, {@link RedeployResult#refused} and
 * {@link RedeployResult#unverified}.
 *
 * <h2>Why every test is bounded</h2>
 * A fake clock that never reaches the deadline it is asked about turns
 * {@code DeployerRecreate#poll} into a busy loop with no network wait left in it at all - measured
 * on this host on 2026-09-15, ten minutes and rising before the {@code timeout} wrapped around the
 * build command killed it. {@link Timeout} here fails the same mistake in seconds instead of
 * hanging the build.
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
        return new DeployerRecreate(delegate, "http://127.0.0.1:" + server.getAddress().getPort(),
                "a-secret", Duration.ofSeconds(5), patience, DeployerRecreate.Waiting.real());
    }

    // -------------------------------------------------------------------------------------------
    // The happy path: accepted, then DONE
    // -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a job that settles DONE is a triggered recreate")
    void settlesDone() throws IOException {
        final Queue<String> jobStates = new ConcurrentLinkedQueue<>(List.of("RUNNING", "DONE"));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/deploy", exchange -> {
            assertEquals("POST", exchange.getRequestMethod());
            assertEquals("a-secret", exchange.getRequestHeaders().getFirst("X-Steward-Token"));
            respond(exchange, 202, "{\"id\":\"job-1\",\"kind\":\"recreate\",\"state\":\"RUNNING\"}");
        });
        server.createContext("/api/jobs/job-1", exchange -> {
            final String state = jobStates.size() > 1 ? jobStates.poll() : jobStates.peek();
            respond(exchange, 200, "{\"id\":\"job-1\",\"state\":\"" + state
                    + "\",\"lines\":[\"pulling smp\",\"Recreating nordtal-s2-smp-1\"]}");
        });
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();

        final RedeployResult result = client(new NoopDelegate(), Duration.ofSeconds(5))
                .deploy("smp");

        assertTrue(result.triggered(), result.message());
        assertTrue(result.verified(), result.message());
        assertTrue(result.message().contains("smp"), result.message());
    }

    // -------------------------------------------------------------------------------------------
    // The route, which is the whole of season-2-ops/140
    // -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("an update run asks the route that pulls, naming the one service")
    void asksTheFetchingRoute() throws IOException {
        // Both routes exist on the real deployer and only one of them fetches. season-2-ops/134
        // made /api/recreate use the image already on this host, which is right for the button an
        // admin presses and wrong for a run whose only reason to touch the container is that the
        // registry has moved past it. Asking the wrong one is silent: the job answers 202, the
        // container comes back healthy, and the image is the stale one. So the stand-in below
        // answers on both and the test says which was used.
        final Queue<String> bodies = new ConcurrentLinkedQueue<>();
        final AtomicInteger recreateCalls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/deploy", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 202, "{\"id\":\"job-4\"}");
        });
        server.createContext("/api/recreate/smp", exchange -> {
            recreateCalls.incrementAndGet();
            respond(exchange, 202, "{\"id\":\"job-4\"}");
        });
        server.createContext("/api/jobs/job-4", exchange -> respond(exchange, 200,
                "{\"id\":\"job-4\",\"state\":\"DONE\",\"lines\":[]}"));
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();

        final RedeployResult result = client(new NoopDelegate(), Duration.ofSeconds(5))
                .deploy("smp");

        assertTrue(result.triggered(), result.message());
        assertEquals(0, recreateCalls.get(),
                "an update run must not ask the route that uses the image already on this host");
        assertEquals(1, bodies.size(), "the deploy route was asked exactly once");
        assertEquals("{\"services\":[\"smp\"]}", bodies.peek());
    }

    @Test
    @DisplayName("starting a standby asks the route that does NOT pull")
    void standbyTakesTheLocalRoute() throws IOException {
        // The mirror image of the test above, and the reason both exist: the two callers want
        // opposite things from the same class. A standby has to come up on the image its live
        // service is running, which on this deployment is very often one built on the host - so a
        // pull here would put the published image under the standby while the live proxy runs the
        // local one, and nothing about the job would say so.
        final Queue<String> bodies = new ConcurrentLinkedQueue<>();
        final AtomicInteger recreateCalls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/deploy", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 202, "{\"id\":\"job-5\"}");
        });
        server.createContext("/api/recreate/limbo-standby", exchange -> {
            recreateCalls.incrementAndGet();
            respond(exchange, 202, "{\"id\":\"job-5\"}");
        });
        server.createContext("/api/jobs/job-5", exchange -> respond(exchange, 200,
                "{\"id\":\"job-5\",\"state\":\"DONE\",\"lines\":[]}"));
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();

        final RedeployResult result = client(new NoopDelegate(), Duration.ofSeconds(5))
                .recreate("limbo-standby");

        assertTrue(result.triggered(), result.message());
        assertEquals(1, recreateCalls.get(), "the standby is made from the image already here");
        assertTrue(bodies.isEmpty(), "starting a standby must not fetch an image");
    }

    @Test
    @DisplayName("the deploy body names one service, never the empty list compose reads as all")
    void bodyNamesOneService() {
        assertEquals("{\"services\":[\"smp\"]}", DeployerRecreate.deployBody("smp"));
        assertTrue(DeployerRecreate.deployBody("a\"b").contains("a\\\"b"),
                "a service name is escaped, not concatenated into the JSON");
    }

    // -------------------------------------------------------------------------------------------
    // The deployer refuses outright
    // -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a non-202 from POST /api/deploy is refused, named")
    void postRefused() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/deploy",
                exchange -> respond(exchange, 400, "\"smp\" is not a compose service"));
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();

        final RedeployResult result = client(new NoopDelegate(), Duration.ofSeconds(5))
                .deploy("smp");

        assertFalse(result.triggered(), result.message());
        assertTrue(result.message().contains("smp"), result.message());
        assertTrue(result.message().contains("400"), result.message());
    }

    // -------------------------------------------------------------------------------------------
    // The job itself fails
    // -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a job that settles FAILED is a refused recreate, carrying its last line")
    void jobFails() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/deploy",
                exchange -> respond(exchange, 202, "{\"id\":\"job-2\"}"));
        server.createContext("/api/jobs/job-2", exchange -> respond(exchange, 200,
                "{\"id\":\"job-2\",\"state\":\"FAILED\",\"lines\":[\"pulling smp\",\"no such image\"]}"));
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();

        final RedeployResult result = client(new NoopDelegate(), Duration.ofSeconds(5))
                .deploy("smp");

        assertFalse(result.triggered(), result.message());
        assertTrue(result.message().contains("no such image"), result.message());
    }

    // -------------------------------------------------------------------------------------------
    // The deployer is not there at all
    // -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("no server listening is refused, not an exception reaching the caller")
    void unreachable() {
        // No HttpServer created or started at all: nothing is listening on this port, which is
        // the "deployer is down" case.
        final DeployerRecreate client = new DeployerRecreate(new NoopDelegate(),
                "http://127.0.0.1:1", "a-secret", Duration.ofSeconds(1), Duration.ofSeconds(5),
                DeployerRecreate.Waiting.real());

        final RedeployResult result = client.deploy("smp");

        assertFalse(result.triggered(), result.message());
        assertTrue(result.message().contains("smp"), result.message());
    }

    // -------------------------------------------------------------------------------------------
    // A job that never settles within this call's patience
    // -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a job still RUNNING when patience runs out is unverified, not refused")
    void neverSettles() throws IOException {
        final AtomicInteger polls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/deploy",
                exchange -> respond(exchange, 202, "{\"id\":\"job-3\"}"));
        server.createContext("/api/jobs/job-3", exchange -> {
            polls.incrementAndGet();
            respond(exchange, 200, "{\"id\":\"job-3\",\"state\":\"RUNNING\",\"lines\":[]}");
        });
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();

        // A clock whose SECOND answer is already past the deadline the FIRST answer set - so the
        // loop polls exactly once and then gives up, without three seconds of a build actually
        // sleeping through POLL_INTERVAL. It has to advance between calls rather than simply
        // starting "in the future": recreate() computes the deadline from one call to now() and
        // poll() compares a LATER call to the same clock against it, so a clock stuck on one
        // instant places every future call before its own deadline forever - which is the bug
        // this comment used to hide, and which hung the real build for ten minutes before it was
        // caught here rather than on the dev host.
        final Instant t0 = Instant.now();
        final AtomicInteger calls = new AtomicInteger();
        final DeployerRecreate client = new DeployerRecreate(new NoopDelegate(),
                "http://127.0.0.1:" + server.getAddress().getPort(), "a-secret",
                Duration.ofSeconds(5), Duration.ofSeconds(5),
                new DeployerRecreate.Waiting() {
                    @Override
                    public Instant now() {
                        // First call (recreate(), setting the deadline): t0. Every call after
                        // that (poll()'s own check): ten seconds past the five-second patience
                        // that first call bought.
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

    private static void respond(final com.sun.net.httpserver.HttpExchange exchange, final int status,
                                final String body) throws IOException {
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
        public @NotNull RuntimeResult runtime() {
            throw new UnsupportedOperationException();
        }

        @Override
        public @NotNull RedeployResult stop(final @NotNull String containerId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public @NotNull RedeployResult start(final @NotNull String containerId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public @NotNull ImageResult images() {
            throw new UnsupportedOperationException();
        }

        @Override
        public @NotNull RedeployResult deploy(final @NotNull String service) {
            throw new UnsupportedOperationException();
        }

        @Override
        public @NotNull RedeployResult recreate(final @NotNull String service) {
            throw new UnsupportedOperationException();
        }
    }
}
