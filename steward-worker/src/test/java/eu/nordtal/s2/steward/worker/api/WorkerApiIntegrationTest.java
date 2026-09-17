package eu.nordtal.s2.steward.worker.api;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import eu.nordtal.s2.steward.worker.docker.Console;
import eu.nordtal.s2.steward.worker.docker.Docker;
import eu.nordtal.s2.steward.worker.docker.DockerOps;
import eu.nordtal.s2.steward.worker.docker.DockerSocket;
import eu.nordtal.s2.steward.worker.host.HostMetrics;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The API steward-ui will call, answered by the daemon that is actually running here.
 *
 * <p>It is served on a port of its own for the length of the test and asked over real HTTP, because
 * what is being checked is the contract - status codes, the token, the shape of the JSON - and none
 * of that is exercised by calling the methods behind it.</p>
 */
class WorkerApiIntegrationTest {

    private static final String PROJECT = "nordtal-s2";
    private static final String TOKEN = "test-token-not-a-secret";
    private static final int PORT = 18082;
    private static final Gson GSON = new Gson();

    private static WorkerApi api;
    private static HttpClient http;

    @BeforeAll
    static void start() {
        final DockerSocket socket = new DockerSocket();
        assumeTrue(socket.isReachable(), "no docker socket - skipping");
        final Docker docker = new Docker(socket);
        api = new WorkerApi(docker, new DockerOps(docker, PROJECT), new Console(docker, PROJECT),
                new HostMetrics(), PROJECT, Path.of("/tmp"), TOKEN, Path.of("/tmp"),
                FakeDirectories.updates(), FakeDirectories.audit(),
                new WorkerApi.Nightly("04:45", ZoneId.of("Europe/Berlin")));
        api.start(PORT);
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterAll
    static void stop() {
        if (api != null) {
            api.close();
        }
    }

    @Test
    @DisplayName("without the token nothing but health answers")
    void theTokenIsTheDoor() throws Exception {
        assertEquals(401, raw("/api/services", false).statusCode());
        assertEquals(200, raw("/api/health", false).statusCode(),
                "health has to answer without a token, or a healthcheck would need the secret");
    }

    @Test
    @DisplayName("the service list carries what the start page's table needs")
    void theTableHasItsColumns() throws Exception {
        final JsonArray services = serviceRows();
        assumeTrue(!services.isEmpty(), "nothing of the stack is running - skipping");

        final JsonObject first = services.get(0).getAsJsonObject();
        for (final String column : new String[]{"service", "state", "hasConsole", "drift"}) {
            assertTrue(first.has(column), column + " is missing from " + first);
        }

        // The four Minecraft services have a console and the others do not - and the interface
        // draws no console field at all for those, rather than a disabled one (§10c).
        boolean sawConsole = false;
        boolean sawNone = false;
        for (var element : services) {
            final JsonObject row = element.getAsJsonObject();
            if (row.get("hasConsole").getAsBoolean()) {
                sawConsole = true;
            } else {
                sawNone = true;
            }
        }
        assertTrue(sawConsole && sawNone,
                "every service answered the same way about its console: " + services);
    }

    @Test
    @DisplayName("the table comes with the age of the image comparison beside it")
    void theDriftAnswerCarriesItsAge() throws Exception {
        final JsonObject table = GSON.fromJson(get("/api/services"), JsonObject.class);

        assertTrue(table.has("services"), "the rows are under `services`: " + table);
        final JsonObject drift = table.getAsJsonObject("drift");
        assertTrue(drift.has("checkedAt"), "no age means the interface cannot say how old it is");
        assertTrue(drift.has("reached"), "whether a registry answered at all is not optional");

        // The envelope exists for exactly this: the answer is cached for a minute, so a page that
        // did not know its age would put a tick next to a comparison of unknown vintage - which is
        // the failure this column was added for (A24). A second call inside the TTL must therefore
        // report the SAME instant, not a fresh one.
        final JsonObject again = GSON.fromJson(get("/api/services"), JsonObject.class);
        assertEquals(drift.get("checkedAt"), again.getAsJsonObject("drift").get("checkedAt"),
                "two calls a moment apart must share one comparison, or nothing is being cached");
    }

    @Test
    @DisplayName("a follow says something of its own, or an idle connection is dropped at thirty seconds")
    void aFollowKeepsItsConnectionAlive() throws Exception {
        final String name = aRunningService();

        final HttpResponse<java.io.InputStream> follow = http.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + PORT + "/api/services/" + name
                                + "/logs?tail=0"))
                .header("X-Steward-Token", TOKEN)
                .header("Accept", "text/event-stream")
                .GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, follow.statusCode());

        // Jetty drops a connection nothing has been written on for thirty seconds - measured on
        // this host, 2026-09-13 - and Javalin's keepAlive() writes nothing; it only holds the
        // request open. A Minecraft server that is having a quiet minute therefore had its log
        // view closed under whoever was watching it, which looks exactly like a server that has
        // stopped. The same comment is what lets steward-ui's end notice a browser that left:
        // the JDK's client only tears down a cancelled stream when something next arrives on it.
        try (var lines = new java.io.BufferedReader(new java.io.InputStreamReader(
                follow.body(), java.nio.charset.StandardCharsets.UTF_8))) {
            // A DAEMON thread, and that is not a detail: closing the response body does not
            // unblock a read already sitting in it - the very JDK behaviour this heartbeat exists
            // to work around - so a plain executor thread would still be parked in readLine() when
            // the suite ended and would hold the test JVM open for ever.
            final var one = java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
                final Thread thread = new Thread(runnable, "heartbeat-test-reader");
                thread.setDaemon(true);
                return thread;
            });
            try {
                assertTrue(one.submit(() -> {
                    String line;
                    while ((line = lines.readLine()) != null) {
                        if (line.startsWith(":") && line.contains(name)) {
                            return true;
                        }
                    }
                    return false;
                }).get(25, java.util.concurrent.TimeUnit.SECONDS), "no heartbeat inside 25 seconds");
            } catch (final java.util.concurrent.TimeoutException never) {
                throw new AssertionError("the follow said nothing at all for 25 seconds");
            } finally {
                one.shutdownNow();
            }
        }
    }

    @Test
    @DisplayName("the nightly clock is readable, so \"tonight\" can mean a moment on this host")
    void theScheduleIsThisHosts() throws Exception {
        final JsonObject schedule = GSON.fromJson(get("/api/schedule"), JsonObject.class);

        assertEquals("04:45", schedule.get("backupAt").getAsString());
        assertEquals("Europe/Berlin", schedule.get("zone").getAsString());
        // An offset, not a local time: the browser has to be able to turn it into an instant, and
        // "04:45" on its own is a number that means something different in every time zone - which
        // is the defect this endpoint exists to end.
        final java.time.ZonedDateTime next = java.time.ZonedDateTime.parse(
                schedule.get("nextBackupAt").getAsString());
        assertTrue(next.isAfter(java.time.ZonedDateTime.now()), "it has already been: " + next);
        assertEquals(45, next.getMinute());
        assertEquals(4, next.getHour(), "read in the zone the worker was given, not this JVM's");
    }

    /**
     * A service with a container actually running, or a skipped test.
     *
     * <h2>Why not the first row</h2>
     * The table lists every service of the project, stopped ones included, so on a host where
     * half the stack is down the first row is a service whose log has no container behind it. The
     * follow then answers 200 and ends at once - which from this side is indistinguishable from
     * the dropped connection the heartbeat exists to prevent. The test failed for a reason that
     * had nothing to do with what it holds, which is the worst kind of red.
     */
    private static String aRunningService() throws Exception {
        for (final var row : serviceRows()) {
            final JsonObject service = row.getAsJsonObject();
            if ("running".equals(service.get("state").getAsString())) {
                return service.get("service").getAsString();
            }
        }
        assumeTrue(false, "no container of the stack is running - skipping");
        throw new AssertionError("unreachable");
    }

    /** The rows out of the envelope. Three tests want them and none of them wants the envelope. */
    private static JsonArray serviceRows() throws Exception {
        return GSON.fromJson(get("/api/services"), JsonObject.class).getAsJsonArray("services");
    }

    @Test
    @DisplayName("a running service can be asked about on its own, with its digests")
    void oneServiceInFull() throws Exception {
        final JsonArray services = serviceRows();
        assumeTrue(!services.isEmpty(), "nothing running - skipping");
        final String name = services.get(0).getAsJsonObject().get("service").getAsString();

        final JsonObject one = GSON.fromJson(get("/api/services/" + name), JsonObject.class);
        assertEquals(name, one.get("service").getAsString());
        assertTrue(one.has("digests"));
        assertTrue(one.get("logLimit").getAsString().contains("50 MB"),
                "the log window has to say out loud what docker has already thrown away");
    }

    @Test
    @DisplayName("a service nobody deploys is a 404, not an empty object")
    void unknownIsNotEmpty() throws Exception {
        assertEquals(404, raw("/api/services/not-a-service", true).statusCode());
    }

    @Test
    @DisplayName("the console refuses a service that has none, with the reason")
    void theConsoleKeepsItsBoundary() throws Exception {
        final HttpResponse<String> refused = post("/api/services/postgres/console",
                "{\"command\":\"list\"}");

        assertEquals(400, refused.statusCode());
        assertTrue(refused.body().contains("postgres"), refused.body());
    }

    @Test
    @DisplayName("an empty console line is refused before it reaches a container")
    void nothingIsNotACommand() throws Exception {
        assertEquals(400, post("/api/services/smp/console", "{\"command\":\"  \"}").statusCode());
    }

    @Test
    @DisplayName("the host's numbers come back, and say that no container has a limit")
    void theHostAnswers() throws Exception {
        final JsonObject host = GSON.fromJson(get("/api/host"), JsonObject.class);

        assertTrue(host.get("memoryTotalBytes").getAsLong() > 0);
        assertTrue(host.get("diskTotalBytes").getAsLong() > 0);
        assertTrue(host.get("containerLimits").getAsString().contains("share of the whole host"),
                "a percentage without that sentence is a number that means something else");
    }

    @Test
    @DisplayName("the log search reads what docker still has, and says when it stopped early")
    void searchReadsWhatDockerStillHas() throws Exception {
        final String name = aRunningService();

        // "e" is in every log line anybody has ever written, which is what makes it a fair probe:
        // the point here is the plumbing and the truncation flag, not the matching.
        final JsonObject found = GSON.fromJson(
                get("/api/services/" + name + "/logs/search?q=e&limit=5"), JsonObject.class);

        assertTrue(found.has("lines"), found.toString());
        assertTrue(found.get("limit").getAsInt() == 5);
        // What it must never do is stop at the limit silently, which is a search that lies by
        // omission - and equally never claim it stopped when it had already found everything, which
        // sends the reader off narrowing a search that was complete. The log of whatever happens to
        // be running is not a fixture, so the exact-limit case is LogSearchTest's; what holds here
        // is the pair of shapes that are wrong either way.
        final int lines = found.getAsJsonArray("lines").size();
        final boolean truncated = found.get("truncated").getAsBoolean();
        assertTrue(lines <= 5, "asked for five lines and got " + lines);
        assertTrue(!truncated || lines == 5,
                "called itself truncated after returning " + lines + " of five");
    }

    @Test
    @DisplayName("a limit of nothing is refused, rather than answered with an empty search")
    void aLimitOfNothingIsNotASearch() throws Exception {
        final String name = aRunningService();

        // Zero lines, always "found nothing", never truncated: an answer indistinguishable from a
        // term that genuinely does not appear.
        assertEquals(400, raw("/api/services/" + name + "/logs/search?q=e&limit=0", true).statusCode());
        assertEquals(400, raw("/api/services/" + name + "/logs/search?q=e&limit=-1", true).statusCode());
    }

    @Test
    @DisplayName("a search for nothing is refused rather than answered with everything")
    void anEmptySearchIsRefused() throws Exception {
        final JsonArray services = serviceRows();
        assumeTrue(!services.isEmpty(), "nothing running - skipping");
        final String name = services.get(0).getAsJsonObject().get("service").getAsString();

        assertEquals(400, raw("/api/services/" + name + "/logs/search?q=", true).statusCode());
    }

    private static String get(final String path) throws Exception {
        final HttpResponse<String> response = raw(path, true);
        assertEquals(200, response.statusCode(), path + " answered " + response.body());
        return response.body();
    }

    private static HttpResponse<String> raw(final String path, final boolean withToken)
            throws Exception {
        final HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + PORT + path));
        if (withToken) {
            request.header("X-Steward-Token", TOKEN);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(final String path, final String body) throws Exception {
        return http.send(HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + PORT + path))
                .header("X-Steward-Token", TOKEN)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }
}
