package eu.nordtal.s2.steward.ui;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import eu.nordtal.s2.steward.ui.auth.DiscordAuth;
import eu.nordtal.s2.steward.ui.config.DatabaseSpec;
import eu.nordtal.s2.steward.ui.config.UiSpec;
import eu.nordtal.s2.steward.ui.data.Data;
import eu.nordtal.s2.steward.ui.worker.WorkerClient;
import io.javalin.Javalin;
import io.javalin.json.JavalinGson;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.IOException;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The interface in front of a stand-in for steward-worker.
 *
 * <h2>Why a stand-in and not the real one</h2>
 * What is under test here is the interface's own half: who may in, what a request without a token
 * is answered with, and whether a container's details reach the browser unchanged. The worker's
 * half has its own tests against the real daemon. A fake here also lets the worker be made to fail
 * on purpose, which is the case the interface most needs to get right and the hardest to arrange
 * with a healthy one.
 *
 * <h2>The sign-in is stood in for, and that is a gap this says out loud</h2>
 * The Discord flow needs a client secret and a registered redirect URI, which only Till can create
 * ({@code todo.md} A29). Everything behind the sign-in is exercised here; the sign-in itself is
 * not, and nothing in these tests should be read as evidence that it works.
 */
class StewardUiIntegrationTest {

    private static final int WORKER_PORT = 18091;
    private static final int UI_PORT = 18090;
    private static final Gson GSON = new Gson();

    private static final AtomicBoolean signedIn = new AtomicBoolean(true);
    private static final AtomicBoolean workerBroken = new AtomicBoolean(false);

    private static Javalin fakeWorker;
    private static StewardUi ui;
    private static HttpClient http;
    private static PostgreSQLContainer<?> postgres;
    private static Data data;
    private static Path configRoot;

    @BeforeAll
    static void start() throws IOException {
        // A stand-in for the config volumes the deployment mounts: one directory per service, and
        // one of them with a file inside a server's data directory, because that is the shape the
        // Paper plugins have and a route that cannot carry a slash would fail only on those.
        configRoot = Files.createTempDirectory("steward-configs");
        Files.createDirectories(configRoot.resolve("steward-worker"));
        Files.writeString(configRoot.resolve("steward-worker/steward.yml"), """
                # The worker.
                port: 8082

                # The shared secret.
                token: hunter2

                # What to stop before a backup.
                stop-services:
                - smp
                - limbo
                """);
        Files.createDirectories(configRoot.resolve("smp/nordtal-smp"));
        Files.writeString(configRoot.resolve("smp/nordtal-smp/config.yml"), """
                # The greeting.
                motd: |-
                  Nordtal
                  Season 2
                """);

        fakeWorker = Javalin.create(cfg -> {
            cfg.jsonMapper(new JavalinGson(new Gson(), true));
            cfg.startup.showJavalinBanner = false;
            cfg.routes.get("/api/health", ctx -> ctx.json(Map.of("status", "ok")));
            cfg.routes.get("/api/services", ctx -> {
                if (workerBroken.get()) {
                    ctx.status(502).result("the daemon is not answering");
                    return;
                }
                ctx.json(List.of(Map.of("service", "smp", "state", "running",
                        "hasConsole", true, "drift", "UP_TO_DATE")));
            });
            cfg.routes.post("/api/services/{name}/console", ctx ->
                    ctx.status(202).json(Map.of("sent", "list")));
        }).start(WORKER_PORT);

        final UiSpec config = new UiSpec() {
            @Override
            public int port() {
                return UI_PORT;
            }

            /**
             * The Ampel's thresholds. Left at the interface's own defaults - this test is about
             * routing and sessions, and a threshold invented here would be a second set of numbers
             * that could drift away from the ones in the spec.
             */
            @Override
            public AlertSpec alerts() {
                return new AlertSpec() {
                };
            }

            @Override
            public WorkerSpec worker() {
                return new WorkerSpec() {
                    @Override
                    public String baseUrl() {
                        return "http://127.0.0.1:" + WORKER_PORT;
                    }

                    @Override
                    public String token() {
                        return "test-token";
                    }
                };
            }

            @Override
            public DiscordSpec discord() {
                return new DiscordSpec() {
                };
            }

            @Override
            public ConfigsSpec configs() {
                return new ConfigsSpec() {
                    @Override
                    public String root() {
                        return configRoot.toString();
                    }
                };
            }
        };

        // A real database with the real migrations: the rows these endpoints read are the rows
        // steward-worker writes, and a stub would prove only that the stub agrees with itself.
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "no docker daemon - skipping");
        postgres = new PostgreSQLContainer<>("postgres:17-alpine");
        postgres.start();
        Flyway.configure(StewardUiIntegrationTest.class.getClassLoader())
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        data = new Data(new DatabaseSpec() {
            @Override
            public String jdbcUrl() {
                return postgres.getJdbcUrl();
            }

            @Override
            public String username() {
                return postgres.getUsername();
            }

            @Override
            public String password() {
                return postgres.getPassword();
            }
        });

        ui = new StewardUi(config, new DiscordAuth(config.discord(), config.publicUrl()),
                new WorkerClient(config.worker().baseUrl(), config.worker().token(),
                        Duration.ofSeconds(5)),
                data,
                ctx -> signedIn.get()
                        ? Optional.of(new DiscordAuth.Account("1", "Till", List.of("admin")))
                        : Optional.empty());
        ui.start(UI_PORT);

        http = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
    }

    @AfterAll
    static void stop() {
        if (ui != null) {
            ui.stop();
        }
        if (fakeWorker != null) {
            fakeWorker.stop();
        }
        if (data != null) {
            data.close();
        }
        if (postgres != null) {
            postgres.stop();
        }
        if (configRoot != null) {
            try (var walk = Files.walk(configRoot)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (final IOException ignored) {
                        // A leftover temp directory is not worth failing a test run over.
                    }
                });
            } catch (final IOException ignored) {
                // Same.
            }
        }
    }

    @Test
    @DisplayName("signed out, the API says no and the sign-in state is readable anyway")
    void signedOutIsNotHalfway() throws Exception {
        signedIn.set(false);
        try {
            assertEquals(401, get("/api/services").statusCode());

            final JsonObject me = GSON.fromJson(get("/api/me").body(), JsonObject.class);
            assertFalse(me.get("signedIn").getAsBoolean());
            // The page has to be able to say WHY nobody can sign in, or an unconfigured deployment
            // looks exactly like a wrong password.
            assertTrue(me.has("signInUnavailable"), me.toString());
            assertTrue(me.get("webauthn").getAsString().contains("not built"),
                    "the missing security key is said out loud, not left to a footnote");
        } finally {
            signedIn.set(true);
        }
    }

    @Test
    @DisplayName("signed in, a container's details arrive unchanged from the worker")
    void theWorkersAnswerIsNotRewritten() throws Exception {
        final HttpResponse<String> response = get("/api/services");

        assertEquals(200, response.statusCode());
        final JsonArray services = GSON.fromJson(response.body(), JsonArray.class);
        assertEquals("smp", services.get(0).getAsJsonObject().get("service").getAsString());
        assertTrue(services.get(0).getAsJsonObject().get("hasConsole").getAsBoolean());
    }

    @Test
    @DisplayName("a write without the CSRF token is refused even with a session")
    void theCookieAloneIsNotEnough() throws Exception {
        // A form posted from another site carries the cookie. It cannot read /api/me.
        final HttpResponse<String> refused = http.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + UI_PORT + "/api/services/smp/console"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"command\":\"list\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(403, refused.statusCode(), refused.body());
    }

    @Test
    @DisplayName("with the token from /api/me, the same write goes through")
    void theTokenFromMeWorks() throws Exception {
        final JsonObject me = GSON.fromJson(get("/api/me").body(), JsonObject.class);
        final String csrf = me.get("csrf").getAsString();

        final HttpResponse<String> accepted = http.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + UI_PORT + "/api/services/smp/console"))
                .header("Content-Type", "application/json")
                .header("X-Steward-CSRF", csrf)
                .POST(HttpRequest.BodyPublishers.ofString("{\"command\":\"list\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(202, accepted.statusCode(), accepted.body());
    }

    @Test
    @DisplayName("when the worker will not answer, the page is told which half is down")
    void aBrokenWorkerIsASentence() throws Exception {
        workerBroken.set(true);
        try {
            final HttpResponse<String> response = get("/api/services");

            assertEquals(502, response.statusCode());
            final JsonObject error = GSON.fromJson(response.body(), JsonObject.class);
            assertEquals("steward-worker", error.get("where").getAsString(),
                    "an empty table would look like a stack with nothing running");
            assertFalse(error.get("error").getAsString().isBlank());
        } finally {
            workerBroken.set(false);
        }
    }

    @Test
    @DisplayName("health answers without a session, because a healthcheck has none")
    void healthIsOpen() throws Exception {
        signedIn.set(false);
        try {
            final JsonObject health = GSON.fromJson(get("/api/health").body(), JsonObject.class);
            assertEquals("ok", health.get("status").getAsString());
            assertTrue(health.get("worker").getAsBoolean(), "the fake worker is up");
        } finally {
            signedIn.set(true);
        }
    }

    @Test
    @DisplayName("asking for a backup writes a row, and the row is what comes back")
    void askingIsARow() throws Exception {
        final JsonObject me = GSON.fromJson(get("/api/me").body(), JsonObject.class);
        final HttpResponse<String> asked = http.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + UI_PORT + "/api/updates"))
                .header("Content-Type", "application/json")
                .header("X-Steward-CSRF", me.get("csrf").getAsString())
                .POST(HttpRequest.BodyPublishers.ofString("{\"kind\":\"BACKUP\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(202, asked.statusCode(), asked.body());
        final JsonObject row = GSON.fromJson(asked.body(), JsonObject.class);
        assertEquals("BACKUP", row.get("kind").getAsString());
        assertEquals("PENDING", row.get("status").getAsString());
        // Who asked is written down, because with three admins the difference between "strange"
        // and "ah, that was you" is a name (§10c).
        assertTrue(row.get("requestedBy").getAsString().contains("Till"), row.toString());

        // And it is in the list the interface draws its runs from.
        final JsonArray recent = GSON.fromJson(get("/api/updates").body(), JsonArray.class);
        assertTrue(recent.size() >= 1);
        assertEquals(row.get("id").getAsLong(),
                recent.get(0).getAsJsonObject().get("id").getAsLong());
    }

    @Test
    @DisplayName("a kind nobody defined is refused before a row is written")
    void nonsenseIsNotARun() throws Exception {
        final JsonObject me = GSON.fromJson(get("/api/me").body(), JsonObject.class);
        final HttpResponse<String> refused = http.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + UI_PORT + "/api/updates"))
                .header("Content-Type", "application/json")
                .header("X-Steward-CSRF", me.get("csrf").getAsString())
                .POST(HttpRequest.BodyPublishers.ofString("{\"kind\":\"DELETE_EVERYTHING\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(400, refused.statusCode(), refused.body());
    }

    @Test
    @DisplayName("the curves come out of postgres, and an empty window is an empty list")
    void theCurvesAreRead() throws Exception {
        final JsonObject answer = GSON.fromJson(
                get("/api/metrics?subject=host&metric=cpu_percent&hours=6").body(), JsonObject.class);

        assertEquals("host", answer.get("subject").getAsString());
        assertTrue(answer.has("points"), answer.toString());
        // Nothing has sampled into this database, so the honest answer is no points - not a
        // fabricated line and not an error.
        assertEquals(0, answer.getAsJsonArray("points").size());
    }

    @Test
    @DisplayName("a metric with no name is refused rather than answered with everything")
    void aCurveNeedsAName() throws Exception {
        assertEquals(400, get("/api/metrics?subject=host").statusCode());
    }

    @Test
    @DisplayName("the season answers with the phase every process reads")
    void theSeasonIsReadable() throws Exception {
        final JsonObject season = GSON.fromJson(get("/api/season").body(), JsonObject.class);
        assertFalse(season.get("phase").getAsString().isBlank());
    }


    // -------------------------------------------------------------------------------------------
    // The configuration of the whole stack
    // -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("every config file under the mount is listed, service directory and all")
    void everyConfigInTheStackIsListed() throws Exception {
        final JsonArray files = GSON.fromJson(get("/api/config").body(), JsonArray.class);

        final List<String> paths = files.asList().stream()
                .map(file -> file.getAsJsonObject().get("path").getAsString())
                .toList();
        assertEquals(List.of("smp/nordtal-smp/config.yml", "steward-worker/steward.yml"), paths);
    }

    @Test
    @DisplayName("a file inside a server's data directory is reachable, slashes and all")
    void aPathWithSlashesInItReachesTheFile() throws Exception {
        // Javalin's `{name}` stops at a slash and `<name>` does not. A plugin's config always
        // lives two directories down, so getting that wrong would 404 every Paper config and
        // nothing else - which would look like a mounting problem for as long as it took to find.
        final HttpResponse<String> response = get("/api/config/smp/nordtal-smp/config.yml");

        assertEquals(200, response.statusCode(), response.body());
        final JsonObject document = GSON.fromJson(response.body(), JsonObject.class);
        assertEquals("smp", document.get("service").getAsString());
        assertEquals("nordtal-smp/config.yml", document.get("name").getAsString());
        final JsonObject motd = document.getAsJsonArray("entries").get(0).getAsJsonObject();
        assertEquals("Motd", motd.get("label").getAsString());
        assertEquals("Nordtal\nSeason 2", motd.get("value").getAsString());
        assertTrue(motd.get("editable").getAsBoolean(), "a block scalar is editable");
    }

    @Test
    @DisplayName("a secret is reported as set and its value never leaves the server")
    void aSecretIsNotSentToTheBrowser() throws Exception {
        final JsonObject document = GSON.fromJson(
                get("/api/config/steward-worker/steward.yml").body(), JsonObject.class);

        final JsonObject token = entry(document, "token");
        assertTrue(token.get("secret").getAsBoolean());
        assertTrue(token.get("filled").getAsBoolean(), "the page still has to be able to say it is set");
        assertFalse(token.has("value"), "the value itself is not in the answer: " + token);
        assertFalse(document.toString().contains("hunter2"), "the secret is nowhere in the body");
    }

    @Test
    @DisplayName("a change is written to the file and the answer is the file as it now reads")
    void aChangeIsWrittenThrough() throws Exception {
        final HttpResponse<String> saved = put("/api/config/steward-worker/steward.yml",
                "{\"changes\": {\"port\": \"9099\"}}");

        assertEquals(200, saved.statusCode(), saved.body());
        assertEquals("9099", entry(GSON.fromJson(saved.body(), JsonObject.class), "port")
                .get("value").getAsString());
        assertTrue(Files.readString(configRoot.resolve("steward-worker/steward.yml"))
                .contains("port: 9099"));
        // And the comment above it is still there, which is the whole reason this reads the file
        // instead of re-dumping it.
        assertTrue(Files.readString(configRoot.resolve("steward-worker/steward.yml"))
                .contains("# The worker."));
    }

    @Test
    @DisplayName("a list arrives as a list and is written as one")
    void aListIsSavedAsAList() throws Exception {
        final HttpResponse<String> saved = put("/api/config/steward-worker/steward.yml",
                "{\"changes\": {\"stop-services\": [\"smp\", \"limbo\", \"hunger-games\"]}}");

        assertEquals(200, saved.statusCode(), saved.body());
        assertTrue(Files.readString(configRoot.resolve("steward-worker/steward.yml"))
                .contains("- hunger-games"));
    }

    @Test
    @DisplayName("one value sent to a list is refused with a sentence, not a stack trace")
    void theWrongShapeIsRefused() throws Exception {
        final HttpResponse<String> refused = put("/api/config/steward-worker/steward.yml",
                "{\"changes\": {\"stop-services\": \"smp\"}}");

        assertEquals(400, refused.statusCode(), refused.body());
        assertTrue(refused.body().contains("stop-services"), refused.body());
    }

    @Test
    @DisplayName("a file outside the mount cannot be asked for, however it is spelled")
    void nothingOutsideTheMountCanBeReached() throws Exception {
        assertEquals(404, get("/api/config/steward-worker/nope.yml").statusCode());
        // The lookup is a comparison against what was found, not a path resolved against the root,
        // so there is no number of decodings that turns this into a file on this host.
        assertFalse(get("/api/config/..%2f..%2fetc%2fpasswd").statusCode() == 200);
        assertFalse(get("/api/config/steward-worker/../../../etc/passwd").statusCode() == 200);
    }

    // -------------------------------------------------------------------------------------------
    // The five admin commands that stayed in the game
    // -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("only the commands declared for the web are offered")
    void theCatalogueIsFiltered() throws Exception {
        final JsonArray offered = GSON.fromJson(get("/api/commands").body(), JsonArray.class);

        assertEquals(
                List.of("/announce", "/hg start", "/smp farmreset now", "/smp milestone unlock",
                        "/smp objective complete"),
                offered.asList().stream()
                        .map(command -> command.getAsJsonObject().get("name").getAsString())
                        .toList());
    }

    @Test
    @DisplayName("a command becomes a row that says who asked for it")
    void aCommandIsARowWithANameOnIt() throws Exception {
        final HttpResponse<String> asked = post("/api/commands",
                "{\"name\": \"/smp milestone unlock\", \"arguments\": {\"key\": \"aufbruch\"}}");

        assertEquals(202, asked.statusCode(), asked.body());
        final long id = Long.parseLong(
                GSON.fromJson(asked.body(), JsonObject.class).get("id").getAsString());

        // The row is real and addressed to the process that owns the command. Reading it back
        // through the same endpoint the browser polls is what proves the round trip, not the 202.
        final JsonObject outcome = GSON.fromJson(get("/api/commands/" + id).body(), JsonObject.class);
        assertEquals("PENDING", outcome.get("status").getAsString());

        // source = WEB is the whole reason V18 exists. A CONSOLE row would have been refused by
        // V11's console-is-anonymous CHECK the moment it carried a Discord id - or, worse, written
        // without one and left the journal unable to say who unlocked a milestone.
        try (var connection = java.sql.DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.prepareStatement(
                     "SELECT source, discord_id, requested_by, command, arguments"
                             + " FROM command_request WHERE id = ?")) {
            statement.setLong(1, id);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next(), "the row is not there");
                assertEquals("WEB", rows.getString("source"));
                assertEquals("1", rows.getString("discord_id"));
                assertTrue(rows.getString("requested_by").contains("Till"),
                        rows.getString("requested_by"));
                assertEquals("smp milestone unlock", rows.getString("command"));
                assertEquals("aufbruch", rows.getString("arguments"));
            }
        }
    }

    @Test
    @DisplayName("a command this interface may not ask for is refused, not written")
    void anUndeclaredCommandIsRefused() throws Exception {
        final HttpResponse<String> refused = post("/api/commands", "{\"name\": \"/smp aura\"}");

        assertEquals(400, refused.statusCode(), refused.body());
        assertTrue(refused.body().contains("/smp aura"), refused.body());
    }

    @Test
    @DisplayName("a missing required argument is refused by the declaration, not by the database")
    void aMissingArgumentIsRefused() throws Exception {
        final HttpResponse<String> refused = post("/api/commands",
                "{\"name\": \"/smp objective complete\", \"arguments\": {}}");

        assertEquals(400, refused.statusCode(), refused.body());
        assertTrue(refused.body().contains("key"), refused.body());
    }

    private static HttpResponse<String> post(final String path, final String body) throws Exception {
        final JsonObject me = GSON.fromJson(get("/api/me").body(), JsonObject.class);
        return http.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + UI_PORT + path))
                .header("Content-Type", "application/json")
                .header("X-Steward-CSRF", me.get("csrf").getAsString())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonObject entry(final JsonObject document, final String path) {
        return document.getAsJsonArray("entries").asList().stream()
                .map(com.google.gson.JsonElement::getAsJsonObject)
                .filter(entry -> entry.get("path").getAsString().equals(path))
                .findFirst()
                .orElseThrow(() -> new AssertionError(path + " is not in " + document));
    }

    private static HttpResponse<String> put(final String path, final String body) throws Exception {
        final JsonObject me = GSON.fromJson(get("/api/me").body(), JsonObject.class);
        return http.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + UI_PORT + path))
                .header("Content-Type", "application/json")
                .header("X-Steward-CSRF", me.get("csrf").getAsString())
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> get(final String path) throws Exception {
        return http.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + UI_PORT + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
