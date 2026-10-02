package eu.nordtal.s2.steward.api;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import eu.nordtal.s2.stewardagent.AgentStandIn;
import io.javalin.Javalin;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The stack routes over real HTTP against the agent's real routes, so status codes and the JSON shape are checked. */
class StackApiIntegrationTest {

    private static final int PORT = 18082;
    private static final Gson GSON = new Gson();

    private static Path scratch;
    private static AgentStandIn agent;
    private static StackApi api;
    private static Javalin server;
    private static HttpClient http;

    @BeforeAll
    static void start() throws java.io.IOException {
        // Short, since a Unix socket path has a length limit the default temp directory can exceed.
        scratch = java.nio.file.Files.createTempDirectory(Path.of("/tmp"), "stack");
        agent = new AgentStandIn(scratch, 0, config -> {});
        api = StackServer.api(agent);
        server = StackServer.start(api, PORT);
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterAll
    static void stop() throws java.io.IOException {
        // The follows first: one closed after Jetty stopped loops in Javalin's error handling.
        if (api != null) {
            api.close();
        }
        if (server != null) {
            server.stop();
        }
        if (agent != null) {
            agent.close();
        }
        try (var files = java.nio.file.Files.walk(scratch)) {
            files.sorted(java.util.Comparator.reverseOrder())
                    .forEach(path -> path.toFile().delete());
        }
    }

    @Test
    void theServiceListCarriesWhatTheStartPagesTableNeeds() throws Exception {
        final JsonArray services = serviceRows();
        assumeTrue(!services.isEmpty(), "nothing of the stack is running - skipping");

        final JsonObject first = services.get(0).getAsJsonObject();
        for (final String column : new String[] {"service", "state", "hasConsole", "drift"}) {
            assertTrue(first.has(column), column + " is missing from " + first);
        }

        // The console comes from compose.yml's label, which the stand-in agent gives smp.
        final JsonObject smp = services.get(0).getAsJsonObject();
        assertEquals("smp", smp.get("service").getAsString());
        assertTrue(smp.get("hasConsole").getAsBoolean(), "smp carries the console label: " + smp);
    }

    @Test
    void theTableComesWithTheAgeOfTheImageComparisonBesideIt() throws Exception {
        final JsonObject table = GSON.fromJson(get("/api/services"), JsonObject.class);

        assertTrue(table.has("services"), "the rows are under `services`: " + table);
        final JsonObject drift = table.getAsJsonObject("drift");
        assertTrue(drift.has("checkedAt"), "no age means the interface cannot say how old it is");
        assertTrue(drift.has("reached"), "whether a registry answered at all is not optional");

        // Cached for a minute, so a second call reports the same instant.
        final JsonObject again = GSON.fromJson(get("/api/services"), JsonObject.class);
        assertEquals(
                drift.get("checkedAt"),
                again.getAsJsonObject("drift").get("checkedAt"),
                "two calls a moment apart must share one comparison, or nothing is being cached");
    }

    @Test
    void aFollowSaysSomethingOfItsOwnOrAnIdleConnectionIsDroppedAtThirtySeconds() throws Exception {
        final String name = aRunningService();

        final HttpResponse<java.io.InputStream> follow = http.send(
                HttpRequest.newBuilder(
                                URI.create("http://127.0.0.1:" + PORT + "/api/services/" + name + "/logs?tail=0"))
                        .header("Accept", "text/event-stream")
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, follow.statusCode());

        // Jetty drops a silent connection after a while, and keepAlive() writes nothing itself.
        try (var lines = new java.io.BufferedReader(
                new java.io.InputStreamReader(follow.body(), java.nio.charset.StandardCharsets.UTF_8))) {
            // A DAEMON thread: closing the response body does not unblock a read already parked in it.
            final var one = java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
                final Thread thread = new Thread(runnable, "heartbeat-test-reader");
                thread.setDaemon(true);
                return thread;
            });
            try {
                assertTrue(
                        one.submit(() -> {
                                    String line;
                                    while ((line = lines.readLine()) != null) {
                                        if (line.startsWith(":") && line.contains(name)) {
                                            return true;
                                        }
                                    }
                                    return false;
                                })
                                .get(25, java.util.concurrent.TimeUnit.SECONDS),
                        "no heartbeat inside 25 seconds");
            } catch (final java.util.concurrent.TimeoutException never) {
                throw new AssertionError("the follow said nothing at all for 25 seconds");
            } finally {
                one.shutdownNow();
            }
        }
    }

    @Test
    void theNightlyClockIsReadableSoTonightCanMeanAMomentOnThisHost() throws Exception {
        final JsonObject schedule = GSON.fromJson(get("/api/schedule"), JsonObject.class);

        assertEquals("04:45", schedule.get("backupAt").getAsString());
        assertEquals("Europe/Berlin", schedule.get("zone").getAsString());
        // An offset, not a local time: the browser must be able to turn it into an instant across every time zone.
        final java.time.ZonedDateTime next =
                java.time.ZonedDateTime.parse(schedule.get("nextBackupAt").getAsString());
        assertTrue(next.toInstant().isAfter(java.time.Instant.now()), "it has already been: " + next);
        assertEquals(45, next.getMinute());
        assertEquals(4, next.getHour(), "read in the zone steward was given, not this JVM's");
    }

    /** A service with a running container, or a skipped test; a stopped one's follow ends at once. */
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

    /** The rows out of the envelope. */
    private static JsonArray serviceRows() throws Exception {
        return GSON.fromJson(get("/api/services"), JsonObject.class).getAsJsonArray("services");
    }

    @Test
    void aRunningServiceCanBeAskedAboutOnItsOwnWithItsDigests() throws Exception {
        final JsonArray services = serviceRows();
        assumeTrue(!services.isEmpty(), "nothing running - skipping");
        final String name = services.get(0).getAsJsonObject().get("service").getAsString();

        final JsonObject one = GSON.fromJson(get("/api/services/" + name), JsonObject.class);
        assertEquals(name, one.get("service").getAsString());
        assertTrue(one.has("digests"));
        // The console offers only the steps steward can fill.
        final int capacity = one.get("logCapacity").getAsInt();
        assertTrue(capacity >= 0 && capacity <= StackApi.LOG_CAPACITY_MAX, "capacity " + capacity);
    }

    @Test
    void aServiceNobodyDeploysIsA404NotAnEmptyObject() throws Exception {
        assertEquals(404, raw("/api/services/not-a-service").statusCode());
    }

    @Test
    void theConsoleRefusesAServiceThatHasNoneWithTheReason() throws Exception {
        final HttpResponse<String> refused = post("/api/services/postgres/console", "{\"command\":\"list\"}");

        assertEquals(400, refused.statusCode());
        assertTrue(refused.body().contains("postgres"), refused.body());
    }

    @Test
    void anEmptyConsoleLineIsRefusedBeforeItReachesAContainer() throws Exception {
        assertEquals(
                400, post("/api/services/smp/console", "{\"command\":\"  \"}").statusCode());
    }

    @Test
    void theHostsNumbersComeBackAndSayThatNoContainerHasALimit() throws Exception {
        final JsonObject host = GSON.fromJson(get("/api/host"), JsonObject.class);

        assertTrue(host.get("memoryTotalBytes").getAsLong() > 0);
        assertTrue(host.get("diskTotalBytes").getAsLong() > 0);
        assertTrue(
                host.get("containerLimits").getAsString().contains("share of the whole host"),
                "a percentage without that sentence is a number that means something else");
    }

    @Test
    void aFinishedArchiveStreamsBackByteForByteWithTheDownloadHeaders() throws Exception {
        final String name = "downloadsAFinishedArchive-20260913T044507Z.tar.zst";
        final Path file = agent.backups.resolve(name);
        final byte[] body = "not a real archive, just some bytes to compare".getBytes(UTF_8);
        java.nio.file.Files.write(file, body);
        try {
            final HttpResponse<byte[]> response = http.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://127.0.0.1:" + PORT + "/api/backups/" + name + "/download"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofByteArray());

            assertEquals(200, response.statusCode());
            assertTrue(
                    response.body().length == body.length && java.util.Arrays.equals(body, response.body()),
                    "the streamed body must be exactly the file's bytes");
            assertEquals(
                    "application/octet-stream",
                    response.headers().firstValue("content-type").orElse(""));
            assertTrue(
                    response.headers()
                            .firstValue("content-disposition")
                            .orElse("")
                            .contains(name),
                    "the download must name the file it is, so a browser's save dialog is not \"download\"");
        } finally {
            java.nio.file.Files.deleteIfExists(file);
        }
    }

    @Test
    void aNameThatIsNotAFinishedBackupIsRefusedBeforeAnyPathIsResolved() throws Exception {
        assertEquals(400, raw("/api/backups/not-a-backup.txt/download").statusCode());
        assertEquals(
                400,
                raw("/api/backups/still-running-20260913T044507Z.tar.zst.partial/download")
                        .statusCode());
    }

    @Test
    void aWellFormedNameThatIsNotActuallyOnDiskIsA404NotA400() throws Exception {
        assertEquals(
                404,
                raw("/api/backups/never-written-20260913T044507Z.tar.zst/download")
                        .statusCode());
    }

    @Test
    void anEncodedTraversalNeverReachesAFileOutsideTheOutputRoot() throws Exception {
        // A client can send %2F for a path separator; the resolved-path check must still refuse it if decoded early.
        final String encoded = "..%2F..%2F..%2Fetc%2Fpasswd-20260913T044507Z.tar.zst";
        final int status = raw("/api/backups/" + encoded + "/download").statusCode();
        assertNotEquals(200, status, "an encoded traversal was answered with a body");
    }

    private static String get(final String path) throws Exception {
        final HttpResponse<String> response = raw(path);
        assertEquals(200, response.statusCode(), path + " answered " + response.body());
        return response.body();
    }

    private static HttpResponse<String> raw(final String path) throws Exception {
        return http.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + PORT + path))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(final String path, final String body) throws Exception {
        return http.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + PORT + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
