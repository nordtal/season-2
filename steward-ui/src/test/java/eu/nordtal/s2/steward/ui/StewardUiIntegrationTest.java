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

import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
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

    @BeforeAll
    static void start() {
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

    private static HttpResponse<String> get(final String path) throws Exception {
        return http.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + UI_PORT + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
