package eu.nordtal.s2.steward.ui;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import eu.nordtal.s2.steward.ui.auth.DiscordAuth;
import eu.nordtal.s2.steward.ui.config.DatabaseSpec;
import eu.nordtal.s2.steward.ui.config.UiSpec;
import eu.nordtal.s2.steward.ui.data.Data;
import eu.nordtal.s2.steward.ui.internal.InternalClient;
import io.javalin.Javalin;
import io.javalin.json.JavalinGson;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
 * <h2>The sign-in is the real one, against a stand-in Discord</h2>
 * These tests used to hand the interface a function that answered "Till" without reading a cookie,
 * which meant the session - creating it, finding it by cookie, ending it - was the one part of the
 * authentication nothing exercised. Now the only thing stood in for is {@code discord.com}, which
 * is the one system boundary in the flow: the state parameter, the role check, the session, the
 * cookie and the CSRF token are all the production code, driven through {@code /auth/login} and
 * {@code /auth/callback} exactly as a browser drives them.
 *
 * <p>What is still Till's ({@code todo.md} A29) is the real Discord application: a client secret
 * and a registered redirect URI. So this proves the flow, not the registration - a redirect URI
 * Discord has not been told about fails at Discord and nowhere in here.</p>
 */
class StewardUiIntegrationTest {

    private static final int WORKER_PORT = 18091;
    private static final int DEPLOYER_PORT = 18092;
    private static final int UI_PORT = 18090;
    private static final int DISCORD_PORT = 18093;
    private static final Gson GSON = new Gson();

    /** The one role that may sign in, as an id, because that is what Discord sends back. */
    private static final String ADMIN_ROLE = "4711";
    private static final String GUILD = "1234";
    private static final String WORKER_TOKEN = "worker-token";

    private static final AtomicBoolean workerBroken = new AtomicBoolean(false);

    /** What the stand-in Discord says this person's roles are. A test turns the admin one off. */
    private static final java.util.concurrent.atomic.AtomicReference<List<String>> memberRoles =
            new java.util.concurrent.atomic.AtomicReference<>(List.of(ADMIN_ROLE, "9999"));

    /** The client secret as it arrived at the stand-in Discord, or null if it never did. */
    private static final java.util.concurrent.atomic.AtomicReference<String> secretDiscordSaw =
            new java.util.concurrent.atomic.AtomicReference<>();

    /**
     * Whether the stand-in worker's log keeps producing <em>lines</em> after its first one.
     *
     * <p>A quiet log is not an edge case, it is the normal one: a healthy Minecraft server says
     * nothing for minutes at a time. It is also the only way to tell two mechanisms apart - a
     * follow that ends because the next line found the session gone, and one that ends because
     * somebody closed the tab. With a chatty log the first hides the second.</p>
     *
     * <p>Quiet means no {@code data:} lines - no log output. The connection still carries a
     * comment now and then, because that is what {@code steward-worker} does: it has the same
     * heartbeat, for the same two reasons, and a stand-in that went completely silent would be
     * testing the interface against a worker that does not exist.</p>
     */
    private static final AtomicBoolean chattyLog = new AtomicBoolean(true);

    /** Whoever is following a log through the stand-in worker right now. */
    private static final List<io.javalin.http.sse.SseClient> workerFollowers =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
     * How many connections are open to the stand-in worker, counted by Jetty itself.
     *
     * <p>Counting SSE clients instead was the obvious thing and it is wrong: a Javalin SSE client
     * is removed when a <em>write to it fails</em>, so a stand-in worker with nothing to say never
     * notices the interface hanging up - it has the same blindness the interface has, and a blind
     * instrument cannot measure whether somebody else can see. The socket is not blind.</p>
     */
    private static final java.util.concurrent.atomic.AtomicInteger workerConnections =
            new java.util.concurrent.atomic.AtomicInteger();

    /** The query string the stand-in worker last saw on a log search - {@code null} for none. */
    private static final java.util.concurrent.atomic.AtomicReference<String> searchQuery =
            new java.util.concurrent.atomic.AtomicReference<>("not called");

    private static Javalin fakeWorker;
    private static Javalin fakeDeployer;
    private static Javalin fakeDiscord;

    /** What the stand-in deployer was last asked to recreate, so a test can read it back. */
    private static final java.util.List<String> recreated =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    private static StewardUi ui;
    private static HttpClient http;
    private static PostgreSQLContainer<?> postgres;
    private static Data data;
    private static Path configRoot;

    @BeforeAll
    static void start() throws Exception {
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
            cfg.jetty.addConnector((server, httpConfiguration) -> {
                final org.eclipse.jetty.server.ServerConnector counted =
                        new org.eclipse.jetty.server.ServerConnector(server,
                                new org.eclipse.jetty.server.HttpConnectionFactory(
                                        httpConfiguration));
                counted.setPort(WORKER_PORT);
                counted.addBean(new org.eclipse.jetty.io.Connection.Listener() {
                    @Override
                    public void onOpened(final org.eclipse.jetty.io.Connection connection) {
                        workerConnections.incrementAndGet();
                    }

                    @Override
                    public void onClosed(final org.eclipse.jetty.io.Connection connection) {
                        workerConnections.decrementAndGet();
                    }
                });
                return counted;
            });
            // THE SAME DOOR THE REAL WORKER HAS, health open and everything else behind the shared
            // secret. A stand-in that lets everybody in passes every test here whether the
            // interface sends its token, sends the deployer's, or sends none at all - which is
            // exactly the mix-up the two-secret split exists to prevent.
            cfg.routes.before("/api/*", ctx -> {
                if (!ctx.path().equals("/api/health")
                        && !WORKER_TOKEN.equals(ctx.header("X-Steward-Token"))) {
                    throw new io.javalin.http.UnauthorizedResponse("bad or missing token");
                }
            });
            cfg.routes.get("/api/health", ctx -> ctx.json(Map.of("status", "ok")));
            cfg.routes.get("/api/services/{name}/logs/search", ctx -> {
                searchQuery.set(ctx.queryString());
                ctx.json(List.of());
            });
            // A log that never ends, which is what a running container's is. Everything about the
            // follow that matters happens in the middle of one: a session ending, a tab closing.
            cfg.routes.sse("/api/services/{name}/logs", client -> {
                client.keepAlive();
                workerFollowers.add(client);
                client.onClose(() -> workerFollowers.remove(client));
                Thread.ofVirtual().start(() -> {
                    client.sendEvent("line", "[12:00:00 INFO]: still running");
                    while (workerFollowers.contains(client)) {
                        try {
                            Thread.sleep(120);
                        } catch (final InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        if (chattyLog.get()) {
                            client.sendEvent("line", "[12:00:00 INFO]: still running");
                        } else {
                            // The real worker's heartbeat, only faster - it beats every ten
                            // seconds and this test is not going to wait that long to find out
                            // whether the interface has hung up.
                            client.sendComment("following " + client.ctx().pathParam("name"));
                        }
                    }
                });
            });
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
        }).start(WORKER_PORT + 100);

        // A stand-in for steward-deployer. It is a second process in the deployment and a second
        // secret, so it is a second stub here too: a single fake answering both would prove the
        // interface works when the two are the same service, which is the thing 3 forbids.
        fakeDeployer = Javalin.create(cfg -> {
            cfg.jsonMapper(new JavalinGson(new Gson(), true));
            cfg.startup.showJavalinBanner = false;
            cfg.routes.before("/api/*", ctx -> {
                if (!ctx.path().equals("/api/health")
                        && !"deployer-token".equals(ctx.header("X-Steward-Token"))) {
                    throw new io.javalin.http.UnauthorizedResponse("bad or missing token");
                }
            });
            cfg.routes.get("/api/health", ctx -> ctx.json(Map.of("status", "ok")));
            cfg.routes.get("/api/services", ctx ->
                    ctx.json(Map.of("smp", "ghcr.io/nordtal/minecraft:latest")));
            cfg.routes.post("/api/recreate/{service}", ctx -> {
                recreated.add(ctx.pathParam("service"));
                ctx.status(202).json(Map.of("id", "job-1", "kind", "recreate",
                        "services", List.of(ctx.pathParam("service")), "state", "RUNNING"));
            });
            cfg.routes.get("/api/jobs/{id}", ctx -> ctx.json(Map.of(
                    "id", ctx.pathParam("id"), "kind", "recreate", "state", "DONE",
                    "exitCode", 0, "lines", List.of("Container nordtal-s2-smp-1  Recreated"))));
        }).start(DEPLOYER_PORT);

        // The one system boundary in the sign-in. Everything else in the flow below - the state,
        // the session, the cookie, the role check - is the interface's own code.
        fakeDiscord = Javalin.create(cfg -> {
            cfg.jsonMapper(new JavalinGson(new Gson(), true));
            cfg.startup.showJavalinBanner = false;
            cfg.routes.post("/oauth2/token", ctx -> {
                final Map<String, String> form = new java.util.LinkedHashMap<>();
                for (final String pair : ctx.body().split("&")) {
                    final int equals = pair.indexOf('=');
                    form.put(pair.substring(0, equals), java.net.URLDecoder.decode(
                            pair.substring(equals + 1), java.nio.charset.StandardCharsets.UTF_8));
                }
                secretDiscordSaw.set(form.get("client_secret"));
                if (!"the-code".equals(form.get("code"))) {
                    ctx.status(400).json(Map.of("error", "invalid_grant"));
                    return;
                }
                ctx.json(Map.of("access_token", "an-access-token", "token_type", "Bearer"));
            });
            cfg.routes.get("/users/@me", ctx -> ctx.json(Map.of("id", "1", "username", "till")));
            cfg.routes.get("/users/@me/guilds/{guild}/member", ctx -> {
                if (!GUILD.equals(ctx.pathParam("guild"))) {
                    ctx.status(404).json(Map.of("message", "Unknown Guild"));
                    return;
                }
                ctx.json(Map.of("nick", "Till", "roles", memberRoles.get()));
            });
        }).start(DISCORD_PORT);

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
                        return WORKER_TOKEN;
                    }
                };
            }

            @Override
            public DiscordSpec discord() {
                return new DiscordSpec() {
                    @Override
                    public String clientId() {
                        return "an-application";
                    }

                    @Override
                    public String clientSecret() {
                        return "a-client-secret";
                    }

                    @Override
                    public String guildId() {
                        return GUILD;
                    }

                    @Override
                    public String adminRole() {
                        return ADMIN_ROLE;
                    }
                };
            }

            @Override
            public DeployerSpec deployer() {
                return new DeployerSpec() {
                    @Override
                    public String baseUrl() {
                        return "http://127.0.0.1:" + DEPLOYER_PORT;
                    }

                    @Override
                    public String token() {
                        return "deployer-token";
                    }
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

        // The production constructor: who is signed in is read out of the session, by the same
        // code the deployment runs. The only substitution is the address of Discord.
        ui = new StewardUi(config,
                new DiscordAuth(config.discord(), config.publicUrl(),
                        "http://127.0.0.1:" + DISCORD_PORT),
                new InternalClient("steward-worker", config.worker().baseUrl(),
                        config.worker().token(), Duration.ofSeconds(5)),
                new InternalClient("steward-deployer", config.deployer().baseUrl(),
                        config.deployer().token(), Duration.ofSeconds(5)),
                data);
        ui.start(UI_PORT);

        http = browser();
        signIn(http);
    }

    @AfterAll
    static void stop() {
        if (ui != null) {
            ui.stop();
        }
        if (fakeWorker != null) {
            fakeWorker.stop();
        }
        if (fakeDeployer != null) {
            fakeDeployer.stop();
        }
        if (fakeDiscord != null) {
            fakeDiscord.stop();
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
        // A browser with its own empty cookie jar, which is what "signed out" actually is. It used
        // to be a boolean in this class, and a boolean cannot tell a missing cookie from a wrong
        // one.
        final HttpClient stranger = browser();

        assertEquals(401, get(stranger, "/api/services").statusCode());

        final JsonObject me = GSON.fromJson(get(stranger, "/api/me").body(), JsonObject.class);
        assertFalse(me.get("signedIn").getAsBoolean());
        // Configured here, so nothing is missing - the sentence for a deployment where something
        // IS missing is DiscordAuthTest's, because it is a property of the configuration and not
        // of a request.
        assertFalse(me.has("signInUnavailable"), me.toString());
        assertTrue(me.get("webauthn").getAsString().contains("not built"),
                "the missing security key is said out loud, not left to a footnote");
    }

    @Test
    @DisplayName("the session is a cookie: the same interface, another browser, is nobody")
    void theSessionIsTheCookieAndNothingElse() throws Exception {
        final JsonObject me = GSON.fromJson(get("/api/me").body(), JsonObject.class);
        assertTrue(me.get("signedIn").getAsBoolean(), me.toString());
        assertEquals("1", me.get("id").getAsString());
        // The nickname from the guild, not the username - somebody's guild nickname is the name
        // the other admins know them by.
        assertEquals("Till", me.get("name").getAsString());
        assertEquals("a-client-secret", secretDiscordSaw.get(),
                "the code was exchanged with the application's secret, not without one");

        assertFalse(GSON.fromJson(get(browser(), "/api/me").body(), JsonObject.class)
                .get("signedIn").getAsBoolean());
    }

    @Test
    @DisplayName("a callback that did not start in this browser is refused before Discord is asked")
    void aCallbackWithoutItsOwnStateIsRefused() throws Exception {
        final HttpClient browser = browser();
        get(browser, "/auth/login");

        final HttpResponse<String> refused =
                get(browser, "/auth/callback?code=the-code&state=somebody-elses");

        assertEquals(400, refused.statusCode(), refused.body());
        assertFalse(GSON.fromJson(get(browser, "/api/me").body(), JsonObject.class)
                .get("signedIn").getAsBoolean(), "a refused callback must not leave a session");
    }

    @Test
    @DisplayName("in the guild but without the role is a refusal that names the person")
    void withoutTheAdminRoleNobodyGetsIn() throws Exception {
        memberRoles.set(List.of("9999"));
        try {
            final HttpClient browser = browser();
            final String state = stateFrom(get(browser, "/auth/login"));

            final HttpResponse<String> refused =
                    get(browser, "/auth/callback?code=the-code&state=" + state);

            assertEquals(403, refused.statusCode(), refused.body());
            assertTrue(refused.body().contains("Till"), refused.body());
            assertFalse(GSON.fromJson(get(browser, "/api/me").body(), JsonObject.class)
                    .get("signedIn").getAsBoolean());
        } finally {
            memberRoles.set(List.of(ADMIN_ROLE, "9999"));
        }
    }

    @Test
    @DisplayName("signing out needs the CSRF token too, and then really ends the session")
    void signingOutIsNotSomethingAnotherSiteCanDo() throws Exception {
        // /auth/logout is not under /api/*, so the filter that guards every write does not see it.
        // A form on any other site can post here carrying the cookie - Javalin leaves SameSite
        // unset - and sign somebody out of the deployment they are watching.
        final HttpClient browser = browser();
        signIn(browser);

        final HttpResponse<String> withoutToken = browser.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + UI_PORT + "/auth/logout"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(403, withoutToken.statusCode(), withoutToken.body());
        assertTrue(GSON.fromJson(get(browser, "/api/me").body(), JsonObject.class)
                .get("signedIn").getAsBoolean(), "the session survived the forged request");

        assertEquals(204, logout(browser).statusCode());
        assertFalse(GSON.fromJson(get(browser, "/api/me").body(), JsonObject.class)
                .get("signedIn").getAsBoolean());
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
        final JsonObject health =
                GSON.fromJson(get(browser(), "/api/health").body(), JsonObject.class);

        assertEquals("ok", health.get("status").getAsString());
        assertTrue(health.get("worker").getAsBoolean(), "the fake worker is up");
    }

    @Test
    @DisplayName("a search with no parameters is forwarded as no parameters, not as `?null`")
    void anEmptyQueryIsNotForwardedAsTheWordNull() throws Exception {
        searchQuery.set("not called");

        assertEquals(200, get("/api/services/smp/logs/search").statusCode());

        // "?null" reaches the worker as a parameter named null with no value, which its own
        // parameter parsing then has to survive - and a search for nothing arrives looking like a
        // search for something.
        assertNull(searchQuery.get(), "the worker saw a query string where there was none");

        get("/api/services/smp/logs/search?q=timeout&limit=5");
        assertEquals("q=timeout&limit=5", searchQuery.get());
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

    // --- steward-deployer: the one thing the interface asks it for (10a.4) --------------------

    @Test
    @DisplayName("recreating a service reaches the deployer, with the deployer's own secret")
    void aRecreateReachesTheDeployer() throws Exception {
        recreated.clear();

        final HttpResponse<String> accepted = post("/api/deployer/recreate/smp", "");

        assertEquals(202, accepted.statusCode(), accepted.body());
        assertEquals(List.of("smp"), recreated,
                "the stand-in deployer refuses any token but its own, so arriving at all is the "
                        + "assertion: the interface sent the deployer's secret and not the worker's");
        assertTrue(accepted.body().contains("job-1"), accepted.body());
    }

    @Test
    @DisplayName("a recreate is in the journal before it happens, naming who asked")
    void aRecreateIsWrittenDown() throws Exception {
        post("/api/deployer/recreate/limbo", "");

        final JsonArray journal = GSON.fromJson(
                get("/api/journal?action=RECREATE").body(), JsonArray.class);
        final JsonObject row = journal.get(0).getAsJsonObject();
        assertEquals("RECREATE", row.get("action").getAsString());
        assertEquals("Till (1)", row.get("actor").getAsString());
        assertEquals("limbo", row.get("subject").getAsString());
    }

    @Test
    @DisplayName("the deployer does not recreate itself, and this end says so rather than compose")
    void theDeployerIsNotOnItsOwnList() throws Exception {
        recreated.clear();

        final HttpResponse<String> refused = post("/api/deployer/recreate/steward-deployer", "");

        assertEquals(400, refused.statusCode(), refused.body());
        assertTrue(recreated.isEmpty(), "the request must not have left this process");
        assertTrue(refused.body().contains("setup script"), refused.body());
    }

    @Test
    @DisplayName("a service name with a slash in it addresses nothing, and is refused as a name")
    void aNameThatIsAPathIsRefused() throws Exception {
        recreated.clear();

        final HttpResponse<String> refused = post("/api/deployer/recreate/smp%2F..%2Fjobs", "");

        assertEquals(400, refused.statusCode(), refused.body());
        assertTrue(recreated.isEmpty(), "the request must not have left this process");
    }

    @Test
    @DisplayName("the job the deployer started can be read back through the interface")
    void aJobIsReadBack() throws Exception {
        final JsonObject job = GSON.fromJson(
                get("/api/deployer/jobs/job-1").body(), JsonObject.class);

        assertEquals("DONE", job.get("state").getAsString());
        assertEquals(0, job.get("exitCode").getAsInt());
        assertTrue(job.getAsJsonArray("lines").toString().contains("Recreated"), job.toString());
    }

    // -------------------------------------------------------------------------------------------
    // The log follow, which is the one thing in this interface that outlives its own request
    // -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a follow ends when the session does, instead of running on in a signed-out tab")
    void loggingOutEndsTheFollow() throws Exception {
        final HttpClient browser = browser();
        signIn(browser);
        final HttpResponse<InputStream> follow = openTheLog(browser);
        final int whileFollowing;
        try (BufferedReader lines = reader(follow)) {
            assertTrue(waitForALineSaying(lines, "still running"), "nothing was ever followed");
            whileFollowing = workerConnections.get();

            assertEquals(204, logout(browser).statusCode());

            // The check at the top of the route is made once, when the connection opens. A follow
            // outlives it by hours: without a second look, a signed-out - or expired, or revoked -
            // session went on being served this container's logs until the container stopped.
            assertTrue(waitForALineSaying(lines, "this session ended"),
                    "the logs kept arriving after the session had been thrown away");
        }
        assertTrue(theFollowsConnectionClosed(whileFollowing), "the worker's end was left open");
    }

    @Test
    @DisplayName("a quiet log is watched for minutes, and its connection goes when the tab does")
    void aQuietFollowSurvivesAndThenIsCleanedUp() throws Exception {
        // THE SLOWEST TEST IN THIS MODULE, AND BOTH HALVES ARE THE POINT. A container that says
        // one thing and then goes quiet is what a healthy Minecraft server is between events, and
        // both defects only exist in that case: a chatty log hides them behind its own traffic.
        chattyLog.set(false);
        try {
            final HttpClient browser = browser();
            signIn(browser);
            // A REAL SOCKET, not an HttpClient. Closing the body of a JDK response leaves the
            // connection open - that is the defect one layer down in this very test's subject -
            // so a "closed tab" made of one would tell the interface nothing and prove nothing.
            // A socket that is closed is closed, which is what a browser does with a tab.
            final java.net.Socket tab = openTheLogOverASocket(browser);
            final BufferedReader lines = new BufferedReader(
                    new InputStreamReader(tab.getInputStream(), StandardCharsets.UTF_8));
            assertTrue(waitForALineSaying(lines, "still running"), "nothing was ever followed");
            final int whileFollowing = workerConnections.get();
            assertTrue(whileFollowing >= 1, "the follow is not open at the worker at all");

            // Jetty drops a connection nothing has written on for thirty seconds - measured here
            // on 2026-09-13. So the interface has to say something of its own, or the log view of
            // a healthy server goes dead half a minute after it was opened and looks, to whoever
            // is watching, exactly like a server that has stopped. Three of them is past the
            // timeout twice over.
            for (int beat = 1; beat <= 3; beat++) {
                assertTrue(waitForALineSaying(lines, "open"),
                        "the follow went quiet and died after beat " + (beat - 1));
            }

            // AND NOW THE TAB IS CLOSED - which means the socket goes, not just the reader. The
            // JDK's own client does not close a connection when the body stream it handed out is
            // closed (measured, 2026-09-13, and it is the same trap the interface itself fell into
            // one layer down), so a test that only closed the reader would be telling the
            // interface nothing at all and would then prove nothing about what it does next.
            tab.close();

            // Every reload used to leave a connection behind here - and behind that one, at the
            // worker, an open docker log stream nobody was reading.
            assertTrue(theFollowsConnectionClosed(whileFollowing),
                    "the worker's end outlived the browser's: " + workerConnections.get()
                            + " connections open, " + whileFollowing + " during the follow");
        } finally {
            chattyLog.set(true);
        }
    }

    /**
     * The same follow, opened by hand over a socket this test can really close.
     *
     * <p>It borrows the session cookie out of the browser's own jar, so it is the same signed-in
     * person - only the transport is one whose closing means something.</p>
     */
    private static java.net.Socket openTheLogOverASocket(final HttpClient browser) throws Exception {
        final java.net.CookieHandler jar = browser.cookieHandler().orElseThrow();
        final String cookies = ((CookieManager) jar).getCookieStore().getCookies().stream()
                .map(cookie -> cookie.getName() + "=" + cookie.getValue())
                .reduce((left, right) -> left + "; " + right)
                .orElseThrow(() -> new AssertionError("this browser has no session cookie"));
        final java.net.Socket tab = new java.net.Socket("127.0.0.1", UI_PORT);
        tab.getOutputStream().write(("GET /api/services/smp/logs HTTP/1.1\r\n"
                + "Host: 127.0.0.1:" + UI_PORT + "\r\n"
                + "Accept: text/event-stream\r\n"
                + "Cookie: " + cookies + "\r\n"
                + "\r\n").getBytes(StandardCharsets.UTF_8));
        tab.getOutputStream().flush();
        return tab;
    }

    private static HttpResponse<InputStream> openTheLog(final HttpClient browser) throws Exception {
        final HttpResponse<InputStream> follow = browser.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + UI_PORT + "/api/services/smp/logs"))
                .header("Accept", "text/event-stream")
                .GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, follow.statusCode());
        return follow;
    }

    private static BufferedReader reader(final HttpResponse<InputStream> follow) {
        return new BufferedReader(new InputStreamReader(follow.body(), StandardCharsets.UTF_8));
    }

    /**
     * Reads the stream until it says that, it ends, or twenty seconds pass.
     *
     * <p>On its own thread, because a socket read that never returns would otherwise hang the whole
     * test run rather than failing it - and "the stream never ended" is precisely the defect these
     * two tests are about.</p>
     */
    private static boolean waitForALineSaying(final BufferedReader lines, final String text)
            throws Exception {
        // A DAEMON thread. Closing a response body does not unblock a read already sitting in it -
        // the JDK behaviour this whole pair of tests is about - so an ordinary executor thread
        // would still be parked in readLine() when the suite ended, and would keep the test JVM
        // alive with nothing to report.
        final ExecutorService one = Executors.newSingleThreadExecutor(runnable -> {
            final Thread thread = new Thread(runnable, "follow-test-reader");
            thread.setDaemon(true);
            return thread;
        });
        try {
            return one.submit(() -> {
                String line;
                while ((line = lines.readLine()) != null) {
                    if (line.contains(text)) {
                        return true;
                    }
                }
                return false;
            }).get(20, TimeUnit.SECONDS);
        } catch (final TimeoutException never) {
            return false;
        } finally {
            one.shutdownNow();
        }
    }

    /**
     * Waits up to three quarters of a minute for the worker's end to close.
     *
     * <p>Long on purpose. On a quiet log nothing discovers a closed tab until the heartbeat writes
     * into it, and the first write after a close often still succeeds - the failure comes on the
     * one after. Two beats plus room is the honest bound, and a test that allowed less would fail
     * on a slow machine while the behaviour was correct.</p>
     */
    private static boolean theFollowsConnectionClosed(final int whileFollowing)
            throws InterruptedException {
        // Strictly fewer than during the follow. Not "back to what it was before", because the
        // client pools connections and may well have followed the log down one it had already
        // opened - in which case the honest evidence is that one MORE connection is gone, not that
        // the count returned to a number it never left.
        for (int attempt = 0; attempt < 450 && workerConnections.get() >= whileFollowing; attempt++) {
            Thread.sleep(100);
        }
        return workerConnections.get() < whileFollowing;
    }

    // --- a browser, and what one does to sign in ----------------------------------------------

    /** One browser: its own cookie jar, and no following of redirects - a test reads them. */
    private static HttpClient browser() {
        return HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
    }

    /** The whole sign-in, driven the way a browser drives it. Nothing here is stood in for. */
    private static void signIn(final HttpClient browser) throws Exception {
        final String state = stateFrom(get(browser, "/auth/login"));

        final HttpResponse<String> callback =
                get(browser, "/auth/callback?code=the-code&state=" + state);

        assertEquals(302, callback.statusCode(), callback.body());
        assertEquals("/", callback.headers().firstValue("Location").orElseThrow());
    }

    /** The one-time value the interface minted into the URL it sent the browser to. */
    private static String stateFrom(final HttpResponse<String> redirect) {
        assertEquals(302, redirect.statusCode(), redirect.body());
        final String location = redirect.headers().firstValue("Location").orElseThrow();
        final Matcher state = Pattern.compile("[?&]state=([^&]+)").matcher(location);
        assertTrue(state.find(), location);
        return state.group(1);
    }

    private static HttpResponse<String> logout(final HttpClient browser) throws Exception {
        final JsonObject me = GSON.fromJson(get(browser, "/api/me").body(), JsonObject.class);
        return browser.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + UI_PORT + "/auth/logout"))
                .header("X-Steward-CSRF", me.get("csrf").getAsString())
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(), HttpResponse.BodyHandlers.ofString());
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
        return get(http, path);
    }

    private static HttpResponse<String> get(final HttpClient browser, final String path)
            throws Exception {
        return browser.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + UI_PORT + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
