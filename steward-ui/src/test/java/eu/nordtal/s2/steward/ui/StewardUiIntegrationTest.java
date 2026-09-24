package eu.nordtal.s2.steward.ui;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import eu.nordtal.s2.steward.ui.auth.DiscordAuth;
import eu.nordtal.s2.steward.ui.auth.Sessions;
import eu.nordtal.s2.steward.ui.auth.TestAuthenticator;
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
import java.util.Locale;
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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
 * <p>What is still Till's is the real Discord application: a client secret and a registered
 * redirect URI. So this proves the flow, not the registration - a redirect URI Discord has not
 * been told about fails at Discord and nowhere in here.</p>
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

    /**
     * Who the stand-in Discord says is signing in - changeable, because the default is the shape
     * of the bug.
     *
     * <p>"Till" and {@code "1"} are eight characters together, and every assertion about the
     * journal in this class was written against them. A real snowflake is 17 to 19 digits and a
     * guild nickname may be 32 characters, so the composed {@code "name (id)"} this interface used
     * to write into {@code audit_log.actor} - {@code varchar(32)} - overflowed for anything but a
     * very short name, and no test here could see it. Making the pair settable is the cheapest
     * honest fix: one sign-in against the real flow, rather than a second copy of this whole
     * fixture with different constants in it.</p>
     */
    private static final java.util.concurrent.atomic.AtomicReference<String> memberId =
            new java.util.concurrent.atomic.AtomicReference<>("1");

    private static final java.util.concurrent.atomic.AtomicReference<String> memberNick =
            new java.util.concurrent.atomic.AtomicReference<>("Till");

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

    /** Kept, so a test can build a second interface against the same database. */
    private static UiSpec config;
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

            // THE REAL ConfigApi, not a stand-in, against a real directory of real files. It is
            // the one part of the worker these tests do not fake, because the whole of the
            // configuration editor now lives on that side and what is left in steward-ui is three
            // lines of proxy. Faking it here would test the proxy against a mirror.
            // No console to type into in this fake worker - none of the fixture files below name a
            // reload command (steward/59), so a no-op is never actually invoked here; the proxy is
            // what this test exercises, not ConfigApi's own reload behaviour, which has its own
            // test in :steward-worker.
            final eu.nordtal.s2.steward.worker.api.ConfigApi configApi =
                    new eu.nordtal.s2.steward.worker.api.ConfigApi(configRoot, (service, command) -> { });
            cfg.routes.get("/api/config", configApi::list);
            cfg.routes.get("/api/config/<file>", configApi::one);
            cfg.routes.put("/api/config/<file>", configApi::save);
            // A log that never ends, which is what a running container's is. Everything about the
            // follow that matters happens in the middle of one: a session ending, a tab closing.
            cfg.routes.sse("/api/services/{name}/logs", client -> {
                client.keepAlive();
                workerFollowers.add(client);
                client.onClose(() -> workerFollowers.remove(client));
                Thread.ofVirtual().start(() -> {
                    client.sendEvent("run", "Earlier run, 22 Sep 19:44");
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
            cfg.routes.get("/users/@me",
                    ctx -> ctx.json(Map.of("id", memberId.get(), "username", "till")));
            cfg.routes.get("/users/@me/guilds/{guild}/member", ctx -> {
                if (!GUILD.equals(ctx.pathParam("guild"))) {
                    ctx.status(404).json(Map.of("message", "Unknown Guild"));
                    return;
                }
                ctx.json(Map.of("nick", memberNick.get(), "roles", memberRoles.get()));
            });
        }).start(DISCORD_PORT);

        config = new UiSpec() {
            @Override
            public int port() {
                return UI_PORT;
            }

            /**
             * The traffic light's thresholds. Left at the interface's own defaults - this test is about
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
            public WebAuthnSpec webauthn() {
                // The defaults, deliberately: relying-party-id `nordtal.eu` against a public-url
                // of https://steward.dev.nordtal.eu is the production pair, and a test fixture
                // that quietly used `localhost` would be testing a combination this deployment
                // never has. The authenticator below claims that origin; nothing in the flow
                // cares what port this JVM is listening on.
                return new WebAuthnSpec() {
                };
            }

            @Override
            public AvatarSpec avatars() {
                return new AvatarSpec() {
                };
            }

            @Override
            public WebPushSpec webPush() {
                // The defaults: both keys blank, which is "not configured" - this test is about
                // routing and sessions, not about a VAPID keypair, and a generated one here would
                // be a keypair this suite invented rather than the one a deployment actually runs.
                return new WebPushSpec() {
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
        // THE FIRST KEY, and every other test in this class rides on it. Since V20 an account with
        // no security key reaches /api/me and nothing else, which is the point of package B - so a
        // fixture that only signed in would now be a fixture in which nothing else works. This is
        // the real ceremony against the real routes; `authenticator` is the software key it uses.
        registerAKey(http, authenticator, "The test key");
    }

    /** The one key account "1" has. Registered once in {@link #start()} and used by everything. */
    private static final TestAuthenticator authenticator = new TestAuthenticator();

    /** The origin the interface expects, which is `public-url`'s and not this JVM's address. */
    private static final String ORIGIN = "https://steward.dev.nordtal.eu";

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
    @DisplayName("a stranger gets the page itself - the bundle is not behind the door")
    void theBundleIsReadableWithoutASession() throws Exception {
        // THE ONE THAT WAS MISSING, AND IT COST THE WHOLE INTERFACE.
        //
        // `guard` runs on `beforeMatched`, which is what makes it impossible for a route to sit
        // outside the arrangement - and `beforeMatched` also runs in front of STATIC FILES, which
        // are not registered through `cfg.routes` and therefore carried no Gate at all. The result
        // was a 500 on `/`: no sign-in page, no Discord button, a white screen with a sentence
        // apologising for itself. GateTest could not see it, because GateTest reads the route
        // table and a static file is not in it.
        //
        // So this one goes over the real port and asks for the thing a person actually opens.
        final HttpClient stranger = browser();
        final HttpResponse<String> page = get(stranger, "/");

        assertEquals(200, page.statusCode(),
                "the page a person opens before signing in: " + page.body());
        assertTrue(page.body().contains("<div id=\"root\""),
                "that should be index.html, not an error page: " + page.body());

        // And the deep path a reload lands on, which takes the SPA fallback rather than the file
        // handler. Same requirement, different mechanism inside Javalin - measured, not assumed.
        final HttpResponse<String> deep = get(stranger, "/operations/runs/27");
        assertEquals(200, deep.statusCode(),
                "reloading a deep link must land on the page it names: " + deep.body());

        // A file next to it, because the browser asks for these before anybody clicks anything.
        assertEquals(200, get(stranger, "/favicon.ico").statusCode());

        // THE TWO THE FALLBACK COULD PLAUSIBLY HAVE BROKEN, and neither is hypothetical.
        //
        // The fallback is a greedy route. If it were consulted before the file handler, the built
        // bundle would be served as index.html and the page would load nothing - a white screen
        // again, by the opposite mistake. So: a real asset still arrives as itself.
        final String index = page.body();
        final int asset = index.indexOf("/assets/");
        assertTrue(asset > 0, "index.html should reference a built asset: " + index);
        final String assetPath = index.substring(asset, index.indexOf('"', asset));
        final HttpResponse<String> built = get(stranger, assetPath);
        assertEquals(200, built.statusCode(), assetPath);
        assertFalse(built.body().contains("<div id=\"root\""),
                assetPath + " came back as the page instead of itself");

        // steward/79: a hashed bundle can never change under its own name, so a cold start must not
        // re-fetch it - and the document that names it can, so a cold start must always revalidate
        // it. The two headers are opposite on purpose; see StewardUi's cacheControl.
        assertEquals(List.of("max-age=31536000, immutable"), built.headers().allValues("Cache-Control"),
                assetPath + " is content-hashed and must be told to cache forever: "
                        + built.headers().map());
        assertEquals(List.of("no-cache"), page.headers().allValues("Cache-Control"),
                "/ carries no hash in its name and must always be revalidated: "
                        + page.headers().map());
        assertEquals(List.of("no-cache"), deep.headers().allValues("Cache-Control"),
                "a client-side route falls back to the same document and needs the same header: "
                        + deep.headers().map());

        // And an endpoint that does not exist answers 404, not 200 with HTML. A caller expecting
        // JSON would otherwise report a parse error and send the next reader after the wrong bug.
        assertEquals(404, get(stranger, "/api/there-is-no-such-thing").statusCode());
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
        // THIS SENTENCE IS PRINTED ON THE SIGN-IN PAGE, so it has to be true on the day it is
        // read - and it has been wrong twice already, both times by outliving what it described.
        // It said the second factor was "not built" after V20 had made a key mandatory, and then
        // it said the key was "not yet" asked for before a dangerous action after packages C and D
        // had built exactly that. Both stale phrasings are asserted against by name, because the
        // failure mode of this line is never a missing sentence - it is an old one.
        final String said = me.get("webauthn").getAsString();
        assertTrue(said.contains("required"), "the sign-in page no longer says a key is needed: "
                + said);
        assertFalse(said.contains("not built"), "the sign-in page still says the second factor"
                + " does not exist, which stopped being true with V20: " + said);
        assertFalse(said.contains("not yet"), "the sign-in page still says the key is not yet"
                + " asked for before a dangerous action, which stopped being true with packages C"
                + " and D: " + said);
        assertTrue(said.contains("every sign-in"), "the sign-in page does not say the key is asked"
                + " for at every sign-in, which is the whole of package C: " + said);
    }

    @Test
    @DisplayName("a stranger at the registration door is told to sign in, not about CSRF")
    void theRegistrationDoorAnswersTheRightRefusal() throws Exception {
        // The two register routes are OUTSIDE /api/*, so the filter never sees them and they repeat
        // its checks by hand. Repeating them in the other order is not equivalent: a browser that
        // has been sitting on the setup page long enough for the session to lapse would be told
        // its request looked cross-site, which is a sentence about an attack that did not happen.
        // 401 is what the rest of the interface says, and it is what the shell knows how to act on.
        final HttpClient stranger = browser();
        for (final String path : new String[] {
                "/auth/webauthn/register/start", "/auth/webauthn/register/finish" }) {
            final HttpResponse<String> refused = stranger.send(HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + UI_PORT + path))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build(), HttpResponse.BodyHandlers.ofString());

            assertEquals(401, refused.statusCode(), path + " answered " + refused.body());
            assertTrue(refused.body().contains("sign in first"), refused.body());
        }
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
    @DisplayName("steward/91: /api/me carries the Discord avatar of the same person row "
            + "/api/people would print, and never fails without one")
    void whoAmICarriesTheDiscordAvatar() throws Exception {
        // No `discord_user` row for "1" exists anywhere else in this class - every other test in
        // here signs in as "1" and none of them ever mirrors a Discord profile onto it. So this is
        // the fallback case FIRST, exactly as every other test already exercises it without
        // knowing: signed in, no person row, and the answer must not carry the field at all.
        final JsonObject withoutARow = GSON.fromJson(get("/api/me").body(), JsonObject.class);
        assertFalse(withoutARow.has("discordAvatarUrl"), withoutARow.toString());

        try (var connection = data.dataSource().getConnection();
             var insert = connection.prepareStatement(
                     "INSERT INTO discord_user (discord_id, discord_avatar_url) VALUES ('1', ?)")) {
            insert.setString(1, "https://cdn.discordapp.com/avatars/1/a.png");
            insert.executeUpdate();
        }
        try {
            final JsonObject withARow = GSON.fromJson(get("/api/me").body(), JsonObject.class);
            assertEquals("https://cdn.discordapp.com/avatars/1/a.png",
                    withARow.get("discordAvatarUrl").getAsString(), withARow.toString());

            // A row that exists but was never mirrored a picture is the same fallback as no row -
            // NULL, not empty text, is what the schema writes for that (V21).
            try (var connection = data.dataSource().getConnection();
                 var clearIt = connection.prepareStatement(
                         "UPDATE discord_user SET discord_avatar_url = NULL WHERE discord_id = '1'")) {
                clearIt.executeUpdate();
            }
            final JsonObject withANullColumn = GSON.fromJson(get("/api/me").body(), JsonObject.class);
            assertFalse(withANullColumn.has("discordAvatarUrl"), withANullColumn.toString());
        } finally {
            // Every other test in this class signs in as "1" and expects the fallback state, so
            // the row this test wrote must not outlive it.
            try (var connection = data.dataSource().getConnection();
                 var delete = connection.prepareStatement(
                         "DELETE FROM discord_user WHERE discord_id = '1'")) {
                delete.executeUpdate();
            }
        }
    }

    @Test
    @DisplayName("the cookie outlives the app being closed, and says so in its own attributes")
    void theCookieSurvivesTheAppBeingClosed() throws Exception {
        // WHY THIS IS ASSERTED ON THE WIRE AND NOT ON A SETTER. Every one of these is a default
        // that Jetty picks and that is wrong for a thing on an iPhone's home screen: without
        // Max-Age the cookie ends when the browser does, which for a standalone web app means
        // whenever iOS wants the memory back, and the next opening is four seconds of Discord
        // redirects to read one number.
        //
        // The cookie is set by /auth/login, not by /auth/callback: the session comes into being the
        // moment the OAuth state is put in it, which is one request before anybody is signed in.
        final HttpClient browser = browser();
        final HttpResponse<String> login = get(browser, "/auth/login");
        final String state = stateFrom(login);
        get(browser, "/auth/callback?code=the-code&state=" + state);

        final String cookie = login.headers().allValues("Set-Cookie").stream()
                .filter(header -> header.startsWith(Sessions.COOKIE + "="))
                .findFirst()
                .orElseGet(() -> fail("the sign-in set no session cookie: "
                        + login.headers().map()));

        assertTrue(cookie.contains("Max-Age="), cookie
                + " has no Max-Age, so it is a browser-session cookie and a home-screen web app"
                + " signs in again every time iOS has ended it");
        assertTrue(cookie.contains("HttpOnly"), cookie + " is readable from JavaScript");
        assertTrue(cookie.toLowerCase(Locale.ROOT).contains("samesite=lax"), cookie
                + " has no SameSite, so what a browser does with it on a cross-site POST is the"
                + " browser's default rather than this application's decision");
        // ...and NOT Secure, because this request arrived over plain http and carried no
        // X-Forwarded-Proto. That is the flag doing its job: behind Caddy the header says https
        // and it appears, in front of a developer on 127.0.0.1 it does not - and a cookie marked
        // Secure on a plain http connection is one the browser refuses to send back, which is a
        // sign-in that never completes and says nothing about why.
        assertFalse(cookie.contains("Secure"), cookie
                + " is marked Secure on a plain http request, so a local sign-in cannot finish");

        // And the other half of the same decision, which is the one the deployment actually runs.
        final HttpClient throughCaddy = browser();
        final HttpResponse<String> behindTls = throughCaddy.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + UI_PORT + "/auth/login"))
                .header("X-Forwarded-Proto", "https")
                .GET().build(), HttpResponse.BodyHandlers.ofString());

        final String secured = behindTls.headers().allValues("Set-Cookie").stream()
                .filter(header -> header.startsWith(Sessions.COOKIE + "="))
                .findFirst()
                .orElseGet(() -> fail("no session cookie: " + behindTls.headers().map()));
        assertTrue(secured.contains("Secure"), secured
                + " is not marked Secure although the proxy said the browser is on https, so the"
                + " session cookie travels in clear text to anybody who can downgrade one request");
    }

    @Test
    @DisplayName("the session survives this service being restarted")
    void theSessionOutlivesTheProcess() throws Exception {
        // THE WHOLE REASON steward_session EXISTS. Until V19 a session was a map in this JVM's
        // heap, so `docker restart steward-ui` - a release, an image update, a crash - signed
        // everybody out, and what an operator then saw was not "please sign in" but three
        // unexplained redirects through discord.com on the next page they opened.
        //
        // Stopping and rebuilding the whole service is the only honest way to assert that. A test
        // that only re-read the row would be asserting that PostgreSQL stores what it is given.
        final HttpClient browser = browser();
        signIn(browser);
        assertTrue(GSON.fromJson(get(browser, "/api/me").body(), JsonObject.class)
                .get("signedIn").getAsBoolean());

        restartTheInterface();

        final JsonObject me = GSON.fromJson(get(browser, "/api/me").body(), JsonObject.class);
        assertTrue(me.get("signedIn").getAsBoolean(),
                "the browser kept its cookie and the row is still there, so this is still a "
                        + "session: " + me);
        assertEquals("Till", me.get("name").getAsString(), me.toString());
    }

    @Test
    @DisplayName("signing in hands out a new session id, so a planted cookie is not promoted")
    void theSessionIdIsRotatedOnSignIn() throws Exception {
        // Session fixation. A row exists before anybody is signed in, because the OAuth state has
        // to live somewhere between /auth/login and /auth/callback. If the callback simply filled
        // that row in, the id a browser was carrying BEFORE the sign-in would be a signed-in id
        // after it - and anybody who can get a cookie value into somebody else's browser (a shared
        // machine, an XSS anywhere under nordtal.eu, a subdomain writing a cookie for the parent)
        // would be signed in as them the moment they signed in.
        final HttpClient browser = browser();
        final HttpResponse<String> login = get(browser, "/auth/login");
        final String before = sessionCookieOf(login);

        final HttpResponse<String> callback =
                get(browser, "/auth/callback?code=the-code&state=" + stateFrom(login));
        final String after = sessionCookieOf(callback);

        assertNotEquals(before, after,
                "the id that was in the browser before anybody proved who they were is now a "
                        + "signed-in session");

        // And the old one is not merely unused - it is gone. A row left behind would still be a
        // valid cookie for whoever planted it, whatever this browser is now carrying.
        final HttpClient planted = browser();
        final HttpResponse<String> withTheOldId = planted.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + UI_PORT + "/api/me"))
                .header("Cookie", Sessions.COOKIE + "=" + before)
                .GET().build(), HttpResponse.BodyHandlers.ofString());

        assertFalse(GSON.fromJson(withTheOldId.body(), JsonObject.class)
                .get("signedIn").getAsBoolean(), withTheOldId.body());
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
        // The cookie is SameSite=Lax, which allows exactly the top-level POST a form on another
        // site performs, so without a token check a stranger's page could sign somebody out of the
        // deployment they are watching.
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

    /**
     * steward/85. Javalin answers a HEAD against a registered GET by discarding the body at the
     * wire layer - but {@code guard}'s {@code beforeMatched} reads {@code ctx.routeRoles()}, and
     * that lookup is keyed to the exact HTTP method. With no route ever registered for
     * {@code HEAD /api/health}, it saw zero decided roles and {@code gateOf} refused it as an
     * undecided route: a 500 that named a fault this service does not have. A real monitor tries
     * HEAD before GET because it is cheaper, so this is exactly the request an outside watcher
     * would send first - and the health route is the one place it has to come back cheap.
     */
    @Test
    @DisplayName("a monitor's HEAD on /api/health gets 200, not the 500 an undecided route gets")
    void headOnHealthIsNotUndecided() throws Exception {
        final HttpResponse<Void> head = browser().send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + UI_PORT + "/api/health"))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build(), HttpResponse.BodyHandlers.discarding());

        assertEquals(200, head.statusCode(),
                "HEAD on an ANYONE route must not be refused as undecided");
    }

    @Test
    @DisplayName("a follow with no parameters is forwarded as no parameters, not as `?null`")
    void anEmptyQueryIsNotForwardedAsTheWordNull() {
        // "?null" reaches the worker as a parameter named null with no value, which its own
        // parameter parsing then has to survive.
        assertEquals("", StewardUi.forwardedQuery(null));
        assertEquals("", StewardUi.forwardedQuery(" "));
        assertEquals("?tail=1000", StewardUi.forwardedQuery("tail=1000"));
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
        // steward/95: the same actor fields the unified actions feed carries, read out of the same
        // string - this fixture's own id ("1") is one digit, so it is not a snowflake and the whole
        // "Till (1)" is the label rather than something resolved through the roster. See
        // ActorFieldsTest for the id-shaped case this fixture cannot exercise on its own.
        assertEquals("Till (1)", row.get("actorLabel").getAsString(), row.toString());
        assertEquals("", row.get("actorDiscordId").getAsString(), row.toString());
        assertFalse(row.get("system").getAsBoolean(), row.toString());

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

    /**
     * A grant of six million years is a slip of the keyboard, and it has to read like one.
     *
     * <p>The only check was {@code days > 0}, so {@code 2147483647} went to PostgreSQL, where
     * {@code make_interval(hours => :days * 24)} overflows an integer and the driver reports it -
     * a 500 blaming this program for a number the operator typed. The ceiling is a decade, which
     * is nine seasons more than anybody will ever buy.</p>
     */
    @Test
    @DisplayName("a grant longer than a decade is refused rather than handed to postgres")
    void anAbsurdGrantIsRefused() throws Exception {
        final HttpResponse<String> refused = post("/api/access/grant",
                "{\"discordId\":\"1\",\"days\":2147483647}");

        assertEquals(400, refused.statusCode(), refused.body());
    }

    /**
     * {@code which} decided between two dates with an {@code equals} and an {@code else}.
     *
     * <p>So {@code "smpstart"}, {@code "launchh"} and a missing field all meant "launch", and the
     * interface answered 200 having overwritten the wrong one of the two dates a whole season
     * hangs off. A value outside the pair is a question this endpoint cannot answer, and the only
     * honest reply is a refusal.</p>
     */
    @Test
    @DisplayName("a season date nobody named is refused, not silently taken for the launch")
    void aSeasonDateNeedsAName() throws Exception {
        final String at = "\"at\":\"2026-10-01T18:00:00Z\"";
        assertEquals(400, post("/api/season/date", "{" + at + ",\"which\":\"smpstart\"}").statusCode());
        assertEquals(400, post("/api/season/date", "{" + at + "}").statusCode());
        assertEquals(400, post("/api/season/date", "{" + at + ",\"which\":\"\"}").statusCode());
        // And the two it does know still work.
        assertEquals(200, post("/api/season/date", "{" + at + ",\"which\":\"smpStart\"}").statusCode());
        assertEquals(200, post("/api/season/date", "{" + at + ",\"which\":\"launch\"}").statusCode());
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
    @DisplayName("the listing says whether a file can be read, before anybody taps it")
    void theListingCarriesReadableAndWritable() throws Exception {
        // Both, and both on every row. The page draws an unreadable file as a dead row with the
        // reason in its title, and a read-only one as a form with no save button - two different
        // drawings it cannot choose between if the listing only carries one of the two flags,
        // which is what it used to carry.
        final JsonArray files = GSON.fromJson(get("/api/config").body(), JsonArray.class);

        assertFalse(files.isEmpty(), "nothing was listed, so nothing was asserted");
        for (final JsonElement listed : files) {
            final JsonObject file = listed.getAsJsonObject();
            final String path = file.get("path").getAsString();
            assertTrue(file.has("readable"), path + " does not say whether it can be read");
            assertTrue(file.has("writable"), path + " does not say whether it can be written");
            // These fixtures are ordinary files this process owns, so both are true here. The
            // false side is asserted where it can be produced: ConfigFilesDiscoverTest, which
            // takes the read bit off a file and skips when it is running as root.
            assertTrue(file.get("readable").getAsBoolean(), path + " should be readable");
            assertTrue(file.get("writable").getAsBoolean(), path + " should be writable");
        }
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
                "{\"revision\": \"" + revisionOf("steward-worker/steward.yml")
                        + "\", \"changes\": {\"port\": \"9099\"}}");

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
                "{\"revision\": \"" + revisionOf("steward-worker/steward.yml")
                        + "\", \"changes\": {\"stop-services\": [\"smp\", \"limbo\","
                        + " \"hunger-games\"]}}");

        assertEquals(200, saved.statusCode(), saved.body());
        assertTrue(Files.readString(configRoot.resolve("steward-worker/steward.yml"))
                .contains("- hunger-games"));
    }

    @Test
    @DisplayName("one value sent to a list is refused with a sentence, not a stack trace")
    void theWrongShapeIsRefused() throws Exception {
        final HttpResponse<String> refused = put("/api/config/steward-worker/steward.yml",
                "{\"revision\": \"" + revisionOf("steward-worker/steward.yml")
                        + "\", \"changes\": {\"stop-services\": \"smp\"}}");

        assertEquals(400, refused.statusCode(), refused.body());
        assertTrue(refused.body().contains("stop-services"), refused.body());
    }

    /**
     * The revision the interface just handed out for a file - which every save has to carry.
     *
     * <p>Read through the API rather than hashed here on purpose: a test that computed the value
     * itself would still pass if the route stopped sending one.</p>
     */
    private String revisionOf(final String file) throws Exception {
        final JsonObject document = GSON.fromJson(get("/api/config/" + file).body(), JsonObject.class);
        return document.get("revision").getAsString();
    }

    @Test
    @DisplayName("a save without a revision is refused, and the file is not touched")
    void aSaveHasToSayWhatItWasLastShown() throws Exception {
        // Deliberately not optional-with-a-default. A save that MAY omit the revision is a save
        // every client can accidentally make unconditional, and the client that forgets is the one
        // that quietly overwrites somebody - which is the failure the whole mechanism exists for,
        // now with a field name to blame it on.
        final byte[] before = Files.readAllBytes(configRoot.resolve("steward-worker/steward.yml"));

        final HttpResponse<String> refused = put("/api/config/steward-worker/steward.yml",
                "{\"changes\": {\"port\": \"9098\"}}");

        assertEquals(400, refused.statusCode(), refused.body());
        assertTrue(refused.body().contains("revision"), refused.body());
        assertArrayEquals(before,
                Files.readAllBytes(configRoot.resolve("steward-worker/steward.yml")),
                "a 400 still wrote the file");
    }

    @Test
    @DisplayName("a save against a revision somebody else has moved on from is a 409 that writes nothing")
    void twoAdminsOnOneFile() throws Exception {
        // The Sunday evening this exists for: two forms open on one file. The second save is not
        // in conflict with the first in any way a merge could resolve - it simply applies its own
        // change to a file it has not seen and writes the result, and the first admin's change is
        // gone with nothing anywhere saying so.
        final String whatTheSecondFormShows = revisionOf("steward-worker/steward.yml");

        assertEquals(200, put("/api/config/steward-worker/steward.yml",
                "{\"revision\": \"" + whatTheSecondFormShows
                        + "\", \"changes\": {\"port\": \"9097\"}}").statusCode());
        final byte[] afterTheFirstSave =
                Files.readAllBytes(configRoot.resolve("steward-worker/steward.yml"));

        final HttpResponse<String> refused = put("/api/config/steward-worker/steward.yml",
                "{\"revision\": \"" + whatTheSecondFormShows
                        + "\", \"changes\": {\"token\": \"hunter3\"}}");

        assertEquals(409, refused.statusCode(), refused.body());
        assertArrayEquals(afterTheFirstSave,
                Files.readAllBytes(configRoot.resolve("steward-worker/steward.yml")),
                "the refused save wrote anyway - the operator is told nothing was saved while the"
                        + " other admin's change is being undone underneath them");
        assertTrue(refused.body().contains("changed by somebody else"), refused.body());

        // And the way out of it, which is the half that makes a 409 usable rather than a wall: the
        // page re-reads the file, the operator decides their change is still the one they want,
        // and the same save goes through against the revision the fresh read handed out.
        final HttpResponse<String> retried = put("/api/config/steward-worker/steward.yml",
                "{\"revision\": \"" + revisionOf("steward-worker/steward.yml")
                        + "\", \"changes\": {\"token\": \"hunter3\"}}");
        assertEquals(200, retried.statusCode(), retried.body());
        assertTrue(Files.readString(configRoot.resolve("steward-worker/steward.yml"))
                .contains("port: 9097"), "the retry undid the other admin's change after all");
    }

    @Test
    @DisplayName("the revision the save answers with is the one the next save can use straight away")
    void aSaveHandsBackWhatTheNextOneNeeds() throws Exception {
        // Otherwise every save is followed by a mandatory reload, and a page that does not know
        // that shows a 409 for the operator's own second edit - the one thing guaranteed to teach
        // somebody that the conflict message is noise.
        final HttpResponse<String> first = put("/api/config/steward-worker/steward.yml",
                "{\"revision\": \"" + revisionOf("steward-worker/steward.yml")
                        + "\", \"changes\": {\"port\": \"9096\"}}");
        assertEquals(200, first.statusCode(), first.body());

        final String handedBack =
                GSON.fromJson(first.body(), JsonObject.class).get("revision").getAsString();
        final HttpResponse<String> second = put("/api/config/steward-worker/steward.yml",
                "{\"revision\": \"" + handedBack + "\", \"changes\": {\"port\": \"9095\"}}");

        assertEquals(200, second.statusCode(), second.body());
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
    // The admin commands the interface offers
    //
    // This heading said "the five admin commands that stayed in the game" until 2026-09-16, and the
    // sentence stopped being true twice over: season-2-ops/18 took every admin command off chat, so
    // none of them stayed in the game, and /phase's four joined the list when the owner sent them to
    // the console and the interface rather than the console alone.
    // -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("only the commands declared for the web are offered")
    void theCatalogueIsFiltered() throws Exception {
        final JsonArray offered = GSON.fromJson(get("/api/commands").body(), JsonArray.class);

        // Alphabetical, because that is the order the catalogue answers in - not a ranking. The
        // twin of this list lives in :commands' WebSurfaceTest; two copies is deliberate here,
        // because this one proves the HTTP endpoint filters and that one proves the declarations
        // agree, and a single shared constant would let both pass while the wiring between them
        // was broken.
        assertEquals(
                List.of("/access settle", "/access unlink", "/announce", "/hg start",
                        "/phase launch", "/phase set", "/phase show", "/phase smp-start",
                        "/smp milestone unlock", "/smp objective complete"),
                offered.asList().stream()
                        .map(command -> command.getAsJsonObject().get("name").getAsString())
                        .toList());

        // Package H, and the half of it that is not "there is a form": the two arguments the
        // interface must NOT draw as text fields say so in the payload. `REFERENCE` and `ACCOUNT`
        // are what the browser switches on to fetch /api/payments/open and /api/people - so a
        // declaration that quietly went back to a WORD would put a six-character reference behind
        // a text field on the one command that books money, and nothing else would notice.
        final JsonObject settle = offered.asList().stream()
                .map(com.google.gson.JsonElement::getAsJsonObject)
                .filter(command -> "/access settle".equals(command.get("name").getAsString()))
                .findFirst().orElseThrow();
        assertEquals("REFERENCE", settle.getAsJsonArray("arguments").get(0).getAsJsonObject()
                .get("kind").getAsString(), settle.toString());

        final JsonObject unlink = offered.asList().stream()
                .map(com.google.gson.JsonElement::getAsJsonObject)
                .filter(command -> "/access unlink".equals(command.get("name").getAsString()))
                .findFirst().orElseThrow();
        assertEquals("ACCOUNT", unlink.getAsJsonArray("arguments").get(0).getAsJsonObject()
                .get("kind").getAsString(), unlink.toString());
    }

    @Test
    @DisplayName("H: settling is asked for by picking a reference, and the row carries it")
    void settlingTravelsAsARow() throws Exception {
        final HttpResponse<String> asked = post("/api/commands",
                "{\"name\": \"/access settle\", \"arguments\": {\"reference\": \"AB12CD\"}}");
        assertEquals(202, asked.statusCode(), asked.body());
        final long id = Long.parseLong(
                GSON.fromJson(asked.body(), JsonObject.class).get("id").getAsString());

        try (var connection = data.dataSource().getConnection();
             var statement = connection.prepareStatement(
                     "SELECT target, command, arguments, source FROM command_request WHERE id = ?")) {
            statement.setLong(1, id);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next(), "the row was not written");
                assertEquals("BOT", rows.getString("target"));
                assertEquals("access settle", rows.getString("command"));
                assertEquals("AB12CD", rows.getString("arguments"));
                assertEquals("WEB", rows.getString("source"));
            }
        }
    }

    @Test
    @DisplayName("H: unlinking takes a Discord id, and nothing that only looks like one")
    void unlinkingTakesAnAccount() throws Exception {
        final HttpResponse<String> asked = post("/api/commands",
                "{\"name\": \"/access unlink\", \"arguments\": {\"member\": \"100000000000000009\"}}");
        assertEquals(202, asked.statusCode(), asked.body());

        // A Minecraft name is what somebody would type if this were a field, and it is exactly
        // what the row cannot carry: the bot reads `member` as a snowflake. Refused here, in a
        // sentence, rather than as an IllegalArgumentException out of RequestArguments.
        final HttpResponse<String> refused = post("/api/commands",
                "{\"name\": \"/access unlink\", \"arguments\": {\"member\": \"Notch\"}}");
        assertEquals(400, refused.statusCode(), refused.body());
        assertTrue(refused.body().contains("pick the person from the list"), refused.body());
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

    @Test
    @DisplayName("an argument sent as a list or an object is a sentence, not a stack trace")
    void anArgumentThatIsNotASingleValueIsRefused() throws Exception {
        // Every branch of CommandApi#encode reaches getAsString(), which answers
        // UnsupportedOperationException on a JsonArray or a JsonObject - a RuntimeException from
        // outside the set of refusals that method is built out of, so Javalin turned it into a 500
        // with a stack trace for what is an ordinary bad request. A form cannot send these; a
        // script, a paste, or a frontend that starts sending multi-selects can.
        final long before = commandRequestCount();

        final HttpResponse<String> asList = post("/api/commands",
                "{\"name\": \"/smp milestone unlock\", \"arguments\": {\"key\": [\"a\", \"b\"]}}");
        assertEquals(400, asList.statusCode(), asList.body());
        assertTrue(asList.body().contains("key") && asList.body().contains("list"), asList.body());

        final HttpResponse<String> asObject = post("/api/commands",
                "{\"name\": \"/smp milestone unlock\", \"arguments\": {\"key\": {\"a\": 1}}}");
        assertEquals(400, asObject.statusCode(), asObject.body());
        assertTrue(asObject.body().contains("key") && asObject.body().contains("structure"),
                asObject.body());

        // Neither of them is a row. A 400 that has already written the request would be the worse
        // half of the bug the journalled submit was introduced to close, from the other direction.
        assertEquals(before, commandRequestCount(),
                "a refused command was written into command_request anyway");
    }

    private static long commandRequestCount() throws Exception {
        try (var connection = java.sql.DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT count(*) FROM command_request")) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
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
        // The Discord id, which is what `audit_log.actor` is documented to hold and what the bot
        // writes there. It used to be "name (id)", and that overflowed varchar(32) for any display
        // name of 11 characters or more - a 500 where the recreate should have been. The name is
        // in the detail now, which is `text` and has room for it.
        assertEquals("1", row.get("actor").getAsString());
        assertTrue(row.get("detail").getAsString().contains("Till"), row.get("detail").getAsString());
        assertEquals("limbo", row.get("subject").getAsString());
    }

    /**
     * The column that could not hold what was being written into it, on every path that writes it.
     *
     * <h2>Why one test and not five</h2>
     * It is one mistake, made five times, and it has one shape: a composed {@code "name (id)"} put
     * into {@code audit_log.actor}, which is {@code varchar(32)} and is documented as the admin's
     * Discord id. A snowflake is 17 to 19 digits, so the brackets and the id alone are 20 to 22
     * characters; any display name of eleven characters or more overflowed. Splitting this into
     * five tests would let four of them stay green while the fifth path was reintroduced, and the
     * thing worth asserting is that <em>no</em> route into this journal composes any more.
     *
     * <h2>Why it needs its own sign-in</h2>
     * Every other test in this class is signed in as {@code Till (1)} - eight characters, which
     * fits with room to spare and is exactly why nothing here saw the bug for as long as it
     * existed. This one signs in a second browser against the same real flow with the stand-in
     * Discord answering a 19-digit snowflake and a 32-character nickname: the maximum Discord
     * allows, which is the case that has to work rather than a case that happens to.
     *
     * <h2>The phase path is the quiet one</h2>
     * {@code PhaseDao} writes its own journal row with {@code cast(:actor AS varchar(32))}, and an
     * explicit cast in PostgreSQL <b>truncates</b> rather than refusing. That path therefore never
     * failed; it wrote half a name into the journal and said nothing, which is worse than the 500
     * the other four gave. So the assertion there is on the value, not on the status code.
     */
    @Test
    @DisplayName("an admin with the longest name Discord allows can do everything, and is journalled by id")
    void aLongDisplayNameIsNotAnOverflow() throws Exception {
        final String snowflake = "1234567890123456789";
        final String longName = "Archibald Fotheringay-Chumleighs";
        assertEquals(19, snowflake.length(), "a Discord snowflake is 17 to 19 digits");
        assertEquals(32, longName.length(), "32 is the longest nickname Discord accepts");
        // What the old form would have produced, and what the column is: the arithmetic, so that
        // nobody has to take the sentence above on trust.
        assertEquals(54, (longName + " (" + snowflake + ")").length());

        memberId.set(snowflake);
        memberNick.set(longName);
        try {
            final HttpClient browser = browser();
            signIn(browser);
            assertEquals(longName, GSON.fromJson(get(browser, "/api/me").body(), JsonObject.class)
                    .get("name").getAsString(), "the stand-in did not take the long name");
            // A different account, so a different key: every /api call below is behind the gate.
            registerAKey(browser, new TestAuthenticator(), "Archibald's key");

            // 1. A command. The row and its journal line are one statement now, so an actor the
            //    column cannot hold does not lose the journal line - it loses the command.
            final HttpResponse<String> asked = post(browser, "/api/commands",
                    "{\"name\": \"/smp milestone unlock\", \"arguments\": {\"key\": \"aufbruch\"}}");
            assertEquals(202, asked.statusCode(), asked.body());
            journalledBy(snowflake, "COMMAND", longName);

            // 2. A recreate. Journalled BEFORE the call, so an overflow here stopped the recreate
            //    from ever being asked for.
            assertEquals(202, post(browser, "/api/deployer/recreate/smp", "").statusCode());
            journalledBy(snowflake, "RECREATE", longName);

            // 3. and 4. Giving access and taking it away - the two things this interface does that
            //    somebody's money is attached to.
            assertEquals(201, post(browser, "/api/access/grant",
                    "{\"discordId\":\"555000000000000001\",\"days\":30}").statusCode());
            journalledBy(snowflake, "GRANT_ACCESS", longName);

            assertEquals(200, post(browser, "/api/access/revoke",
                    "{\"discordId\":\"555000000000000001\"}").statusCode());
            journalledBy(snowflake, "REVOKE_ACCESS", longName);

            // 5. The two season dates, and the phase. These never threw: PhaseDao casts the actor
            //    to varchar(32) explicitly, and an explicit cast in PostgreSQL TRUNCATES. So the
            //    assertion is on the value written, not on the status code - a half-name in the
            //    journal is the failure, and it is a silent one.
            //
            //    The dates are rewritten to whatever they already say, so this test moves no state
            //    another test depends on. Setting a date to the value it already has still writes
            //    the journal row, which is all that is being read here - and it cannot fall foul of
            //    the launch/smp-start ordering rules, because it does not change the ordering.
            final JsonObject season = GSON.fromJson(get("/api/season").body(), JsonObject.class);
            final String phaseBefore = season.get("phase").getAsString();
            final String launchAt = season.has("launch")
                    ? season.get("launch").getAsString() : "2026-10-01T18:00:00Z";
            final String smpStartAt = season.has("smpStart")
                    ? season.get("smpStart").getAsString() : "2026-10-01T18:00:00Z";

            assertEquals(200, post(browser, "/api/season/date",
                    "{\"at\":\"" + launchAt + "\",\"which\":\"launch\"}").statusCode());
            assertEquals(snowflake, actorOf("SET_LAUNCH"),
                    "the season journal took a truncated actor and said nothing");

            assertEquals(200, post(browser, "/api/season/date",
                    "{\"at\":\"" + smpStartAt + "\",\"which\":\"smpStart\"}").statusCode());
            assertEquals(snowflake, actorOf("SET_SMP_START"));

            // START_EVENT and never SMP: setSmpStart refuses outright once the season is in SMP,
            // and leaving the network in that phase would make the date tests beside this one fail
            // for a reason that has nothing to do with them.
            try {
                assertEquals(200, post(browser, "/api/season/phase",
                        "{\"phase\":\"START_EVENT\",\"reason\":\"a long name should not matter\"}")
                        .statusCode());
                assertEquals(snowflake, actorOf("SET_PHASE"));
            } finally {
                post(browser, "/api/season/phase",
                        "{\"phase\":\"" + phaseBefore + "\",\"reason\":\"restoring the fixture\"}");
            }
        } finally {
            memberId.set("1");
            memberNick.set("Till");
        }
    }

    /** The newest journal row of this action: written by that id, and naming the person in its detail. */
    private static void journalledBy(final String id, final String action, final String name)
            throws Exception {
        final JsonObject row = newestJournalRow(action);
        assertEquals(id, row.get("actor").getAsString(),
                action + " was journalled as something other than the bare Discord id: " + row);
        // The name is not lost, it moved. `detail` is `text`, so it has room for it, and without
        // it the journal page would name nobody a person recognises.
        assertTrue(row.get("detail").getAsString().contains(name),
                action + " lost the name entirely instead of moving it into the detail: " + row);
    }

    private static String actorOf(final String action) throws Exception {
        return newestJournalRow(action).get("actor").getAsString();
    }

    private static JsonObject newestJournalRow(final String action) throws Exception {
        final JsonArray journal = GSON.fromJson(
                get("/api/journal?action=" + action).body(), JsonArray.class);
        assertFalse(journal.isEmpty(), "nothing was journalled as " + action);
        return journal.get(0).getAsJsonObject();
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
    // The second factor (§10a, package B): a key is registered, and without one nothing works
    // -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("an account with no key reaches /api/me and nothing else")
    void withoutAKeyThereIsNowhereToGo() throws Exception {
        memberId.set("770000000000000001");
        memberNick.set("Newcomer");
        try {
            final HttpClient browser = browser();
            signIn(browser);

            // The sign-in worked. This is not a refused Discord login and must not look like one.
            final JsonObject me = GSON.fromJson(get(browser, "/api/me").body(), JsonObject.class);
            assertTrue(me.get("signedIn").getAsBoolean(), me.toString());
            assertEquals(0, me.getAsJsonArray("keys").size(), me.toString());
            assertFalse(me.get("verified").getAsBoolean(), me.toString());

            // And everything else is shut. 403 rather than 401, because 401 is what the shell
            // turns into the sign-in page - sending this person back to Discord would be a loop
            // they have already been round.
            final HttpResponse<String> refused = get(browser, "/api/services");
            assertEquals(403, refused.statusCode(), refused.body());
            assertEquals("SECOND_FACTOR_MISSING",
                    GSON.fromJson(refused.body(), JsonObject.class).get("code").getAsString(),
                    "the interface cannot tell this apart from an ordinary refusal: "
                            + refused.body());

            // Including the ones that write. A missing key is not a read-only mode.
            assertEquals(403, post(browser, "/api/updates", "{\"kind\":\"UPDATE\"}").statusCode());
        } finally {
            memberId.set("1");
            memberNick.set("Till");
        }
    }

    @Test
    @DisplayName("registering a key opens the interface, and the journal says who did it")
    void aKeyIsRegisteredAndThenEverythingWorks() throws Exception {
        memberId.set("770000000000000002");
        memberNick.set("Registrant");
        try {
            final HttpClient browser = browser();
            signIn(browser);
            assertEquals(403, get(browser, "/api/services").statusCode(), "the gate was open");

            registerAKey(browser, new TestAuthenticator(), "YubiKey blau");

            assertEquals(200, get(browser, "/api/services").statusCode(),
                    "the key was registered and the gate stayed shut");

            final JsonObject me = GSON.fromJson(get(browser, "/api/me").body(), JsonObject.class);
            assertEquals(1, me.getAsJsonArray("keys").size(), me.toString());
            assertEquals("YubiKey blau", me.getAsJsonArray("keys").get(0).getAsJsonObject()
                    .get("label").getAsString());
            // Registering a key IS holding it, so the session is verified without a second
            // ceremony one second later.
            assertTrue(me.get("verified").getAsBoolean(), me.toString());

            journalledBy("770000000000000002", "REGISTER_KEY", "YubiKey blau");
        } finally {
            memberId.set("1");
            memberNick.set("Till");
        }
    }

    @Test
    @DisplayName("a registration answer cannot be replayed")
    void aChallengeIsAnsweredExactlyOnce() throws Exception {
        memberId.set("770000000000000003");
        memberNick.set("Replayer");
        try {
            final HttpClient browser = browser();
            signIn(browser);
            final TestAuthenticator key = new TestAuthenticator();

            final HttpResponse<String> started =
                    post(browser, "/auth/webauthn/register/start", "");
            assertEquals(200, started.statusCode(), started.body());

            assertEquals(200, finishRegistration(browser,
                    key.register(started.body(), ORIGIN), "Once").statusCode());

            // A DIFFERENT AUTHENTICATOR ANSWERING THE SAME CHALLENGE, and that detail is the whole
            // test. Sending the identical bytes twice proves nothing: the library refuses the
            // second one because that credential id is already registered, so the test passed with
            // the read-and-clear replaced by a plain SELECT - measured, which is why it reads like
            // this now. The attack is somebody who has SEEN the challenge registering THEIR key
            // with it, and only clearing it in the statement that reads it stops that.
            final HttpResponse<String> again = finishRegistration(browser,
                    new TestAuthenticator().register(started.body(), ORIGIN), "Twice");
            assertEquals(400, again.statusCode(), again.body());
            assertEquals(1, GSON.fromJson(get(browser, "/api/me").body(), JsonObject.class)
                    .getAsJsonArray("keys").size(),
                    "a second authenticator answered a challenge that had already been used");
        } finally {
            memberId.set("1");
            memberNick.set("Till");
        }
    }

    @Test
    @DisplayName("a key that answers from another origin is refused")
    void anotherOriginIsRefused() throws Exception {
        memberId.set("770000000000000004");
        memberNick.set("Elsewhere");
        try {
            final HttpClient browser = browser();
            signIn(browser);

            final HttpResponse<String> started =
                    post(browser, "/auth/webauthn/register/start", "");
            // A SUBDOMAIN OF THE RELYING PARTY, not a stranger's domain - because that is the case
            // the relying party id actually creates. `nordtal.eu` means a key registered here works
            // on every subdomain; it must NOT mean this service accepts a ceremony that happened on
            // one. A browser on bluemap.nordtal.eu can ask for these keys; it cannot hand the
            // answer to Steward.
            final String answer = new TestAuthenticator()
                    .register(started.body(), "https://bluemap.nordtal.eu");

            final HttpResponse<String> refused = finishRegistration(browser, answer, "From next door");
            assertEquals(400, refused.statusCode(), refused.body());
            assertEquals(0, GSON.fromJson(get(browser, "/api/me").body(), JsonObject.class)
                    .getAsJsonArray("keys").size(), "a foreign origin registered a key");
        } finally {
            memberId.set("1");
            memberNick.set("Till");
        }
    }

    @Test
    @DisplayName("an answer to a challenge nobody issued is refused")
    void aChallengeNobodyIssuedIsRefused() throws Exception {
        memberId.set("770000000000000005");
        memberNick.set("Inventor");
        try {
            final HttpClient browser = browser();
            signIn(browser);

            final HttpResponse<String> started =
                    post(browser, "/auth/webauthn/register/start", "");
            final String answer = new TestAuthenticator().register(started.body(), ORIGIN,
                    // 32 bytes of somebody else's choosing, base64url - the shape is right and the
                    // value was never issued by this service.
                    "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8");

            final HttpResponse<String> refused = finishRegistration(browser, answer, "Invented");
            assertEquals(400, refused.statusCode(), refused.body());
        } finally {
            memberId.set("1");
            memberNick.set("Till");
        }
    }

    @Test
    @DisplayName("a second key needs the first one, so a stolen cookie cannot add its own")
    void aSecondKeyNeedsTheFirst() throws Exception {
        // A SECOND SESSION OF AN ACCOUNT THAT ALREADY HAS A KEY. It gets past the gate - package C
        // is what will make every session prove a key - but it may not register another one, which
        // would otherwise be the cheapest way for a stolen cookie to become a permanent key.
        final HttpClient stolen = browser();
        signIn(stolen);
        assertFalse(GSON.fromJson(get(stolen, "/api/me").body(), JsonObject.class)
                .get("verified").getAsBoolean(), "a fresh session started out verified");

        final HttpResponse<String> refused = post(stolen, "/auth/webauthn/register/start", "");
        assertEquals(403, refused.statusCode(), refused.body());
        assertTrue(refused.body().contains("already has a key"), refused.body());
    }

    // -------------------------------------------------------------------------------------------
    // Packages C and D: the key at every sign-in, and again before anything that changes something
    // -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("C: Discord alone is not enough - a fresh session sees nothing until it holds the key")
    void discordAloneIsNotEnough() throws Exception {
        // The same account as everywhere else in this class, so it HAS a key: this is not the
        // setup case, it is the case the setup case used to be mistaken for. A cookie that has
        // completed the Discord redirect and nothing else.
        final HttpClient fresh = browser();
        signIn(fresh);

        final JsonObject me = GSON.fromJson(get(fresh, "/api/me").body(), JsonObject.class);
        assertTrue(me.get("signedIn").getAsBoolean(), me.toString());
        assertFalse(me.get("verified").getAsBoolean(), "a fresh session started out verified");
        assertFalse(me.getAsJsonArray("keys").isEmpty(), "this account should have a key already");

        // Reading is refused too, and that is the whole of C. Before it, everything below was
        // readable for thirty days to anybody holding the cookie.
        for (final String path : new String[] {
                "/api/services", "/api/people", "/api/journal", "/api/updates", "/api/config" }) {
            final HttpResponse<String> refused = get(fresh, path);
            assertEquals(403, refused.statusCode(), path + " answered " + refused.body());
            assertEquals("SECOND_FACTOR_REQUIRED",
                    GSON.fromJson(refused.body(), JsonObject.class).get("code").getAsString(),
                    path + " answered " + refused.body());
        }

        holdTheKey(fresh, authenticator);

        assertEquals(200, get(fresh, "/api/people").statusCode());
        assertTrue(GSON.fromJson(get(fresh, "/api/me").body(), JsonObject.class)
                .get("verified").getAsBoolean(), "the key was held and the session is not verified");
    }

    @Test
    @DisplayName("C: a challenge answers once - the second time is not a sign-in, it is a replay")
    void aChallengeIsSpentWhenItIsAnswered() throws Exception {
        final HttpClient browser = browser();
        signIn(browser);
        final HttpResponse<String> started =
                post(browser, "/auth/webauthn/authenticate/start", "");
        assertEquals(200, started.statusCode(), started.body());
        final String answer = authenticator.assertion(started.body(), ORIGIN);

        assertEquals(200, finishAssertion(browser, answer).statusCode());
        // The same bytes again. The column was emptied in the same statement that read it, so
        // there is nothing to check this against - which is the point: a recording of somebody
        // else's successful sign-in is not a sign-in.
        final HttpResponse<String> replayed = finishAssertion(browser, answer);
        assertEquals(400, replayed.statusCode(), replayed.body());
        assertTrue(replayed.body().contains("already finished")
                || replayed.body().contains("not started"), replayed.body());
    }

    @Test
    @DisplayName("C: somebody else's key does not open this session")
    void anotherKeyIsNotThisAccountsKey() throws Exception {
        final HttpClient browser = browser();
        signIn(browser);
        final HttpResponse<String> started =
                post(browser, "/auth/webauthn/authenticate/start", "");
        assertEquals(200, started.statusCode(), started.body());

        // A key this service has never seen, answering a challenge it really was issued. The
        // signature verifies against ITS OWN public key and against nothing this account has - so
        // the only thing standing here is the lookup, which is exactly what is being tested.
        final HttpResponse<String> refused =
                finishAssertion(browser, new TestAuthenticator().assertion(started.body(), ORIGIN));
        assertEquals(400, refused.statusCode(), refused.body());
        assertFalse(GSON.fromJson(get(browser, "/api/me").body(), JsonObject.class)
                .get("verified").getAsBoolean(), "a stranger's key verified this session");
    }

    @Test
    @DisplayName("C: a browser that was never on this address does not get in")
    void theOriginIsCheckedOnASignInToo() throws Exception {
        final HttpClient browser = browser();
        signIn(browser);
        final HttpResponse<String> started =
                post(browser, "/auth/webauthn/authenticate/start", "");
        final HttpResponse<String> refused = finishAssertion(browser,
                authenticator.assertion(started.body(), "https://bluemap.nordtal.eu"));

        // The relying party id is the whole of nordtal.eu so that a key survives the move to
        // production; the ORIGIN is this one address. Without that narrower check, any subdomain
        // could relay a ceremony through this service - which is the risk the plan writes down as
        // the price of the wide id, and this is the thing that pays it.
        assertEquals(400, refused.statusCode(), refused.body());
        assertFalse(GSON.fromJson(get(browser, "/api/me").body(), JsonObject.class)
                .get("verified").getAsBoolean(), "a ceremony from another subdomain verified");
    }

    @Test
    @DisplayName("D: reading stays open after five minutes, writing asks again")
    void theWindowClosesOnWritingAndNotOnReading() throws Exception {
        final HttpClient browser = browser();
        signIn(browser);
        holdTheKey(browser, authenticator);
        assertEquals(202, post(browser, "/api/updates", "{\"kind\":\"BACKUP\"}").statusCode());

        heldLongAgo(browser);

        // Reading is KEY_HELD: the key was held in this session, and that does not expire. An
        // interface that logged somebody out of the service table every five minutes would be an
        // interface nobody watches a deployment in.
        assertEquals(200, get(browser, "/api/services").statusCode());

        final HttpResponse<String> refused =
                post(browser, "/api/updates", "{\"kind\":\"BACKUP\"}");
        assertEquals(403, refused.statusCode(), refused.body());
        final JsonObject body = GSON.fromJson(refused.body(), JsonObject.class);
        assertEquals("SECOND_FACTOR_REQUIRED", body.get("code").getAsString(), refused.body());
        // `retryable` is what tells the interface to open the dialog and send the request again
        // rather than to draw a red box. Without it this is indistinguishable from a refusal.
        assertTrue(body.get("retryable").getAsBoolean(), refused.body());

        // And holding it again lets the SAME request through - which is the whole of "one tap, not
        // two" as the server sees it.
        holdTheKey(browser, authenticator);
        assertEquals(202, post(browser, "/api/updates", "{\"kind\":\"BACKUP\"}").statusCode());
    }

    @Test
    @DisplayName("D: every writing route is behind the window, not just the ones somebody remembered")
    void everyWriteAsksAgain() throws Exception {
        final HttpClient browser = browser();
        signIn(browser);
        holdTheKey(browser, authenticator);
        heldLongAgo(browser);

        // Four writes from four different corners of the service, INCLUDING the two Till moved
        // across on 2026-09-14 - saving a config file and switching the season phase were "not
        // dangerous" in the plan and are behind the key now. A test that only listed the obvious
        // ones would pass on exactly the day somebody moved one back.
        final String[][] writes = {
                { "/api/updates", "{\"kind\":\"UPDATE\"}" },
                { "/api/access/grant", "{\"discordId\":\"1\",\"days\":1}" },
                { "/api/season/phase", "{\"phase\":\"LIVE\"}" },
                { "/api/commands", "{\"command\":\"phase\"}" },
        };
        for (final String[] write : writes) {
            final HttpResponse<String> refused = post(browser, write[0], write[1]);
            assertEquals(403, refused.statusCode(), write[0] + " answered " + refused.body());
            assertEquals("SECOND_FACTOR_REQUIRED",
                    GSON.fromJson(refused.body(), JsonObject.class).get("code").getAsString(),
                    write[0] + " answered " + refused.body());
        }
    }

    // -------------------------------------------------------------------------------------------
    // Package F: two keys are comfortable, not merely possible
    // -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("F: a second key can be added, named, renamed and removed")
    void aSecondKeyIsOrdinaryWork() throws Exception {
        final HttpClient browser = browser();
        signIn(browser);
        holdTheKey(browser, authenticator);

        final TestAuthenticator second = new TestAuthenticator();
        registerAKey(browser, second, "My phone");

        final JsonObject me = GSON.fromJson(get(browser, "/api/me").body(), JsonObject.class);
        final JsonObject added = me.getAsJsonArray("keys").asList().stream()
                .map(com.google.gson.JsonElement::getAsJsonObject)
                .filter(key -> "My phone".equals(key.get("label").getAsString()))
                .findFirst().orElseThrow(() -> new AssertionError(me.toString()));
        final String id = added.get("id").getAsString();

        assertEquals(200, put(browser, "/api/keys/" + id, "{\"label\":\"My old phone\"}")
                .statusCode());
        assertTrue(get(browser, "/api/me").body().contains("My old phone"));

        assertEquals(200, delete(browser, "/api/keys/" + id).statusCode());
        assertFalse(get(browser, "/api/me").body().contains("My old phone"));
        // The first key is untouched, which is the thing a remove has to get right: the account
        // still works afterwards.
        assertEquals(200, get(browser, "/api/services").statusCode());
    }

    @Test
    @DisplayName("F: a key id from somewhere else is not a way to remove somebody's key")
    void aKeyIsRemovedOnlyFromTheAccountItIsOn() throws Exception {
        final HttpClient browser = browser();
        signIn(browser);
        holdTheKey(browser, authenticator);
        // A credential id is handed to every browser that starts a sign-in, so it is a value a
        // stranger can hold. The DELETE matches on the account as well, and this is that column.
        final HttpResponse<String> refused =
                delete(browser, "/api/keys/" + new TestAuthenticator().credentialId());
        assertEquals(404, refused.statusCode(), refused.body());
    }

    @Test
    @DisplayName("F: managing keys is behind the key, freshly held")
    void addingAKeyIsAsPowerfulAsHavingOne() throws Exception {
        final HttpClient browser = browser();
        signIn(browser);
        holdTheKey(browser, authenticator);
        heldLongAgo(browser);
        // A stolen session that could add an authenticator would be a stolen session that had made
        // itself permanent. That is why key management is in the plan's dangerous list at all.
        assertEquals(403, delete(browser, "/api/keys/" + authenticator.credentialId()).statusCode());
        assertEquals(403, put(browser, "/api/keys/" + authenticator.credentialId(),
                "{\"label\":\"mine now\"}").statusCode());
    }

    // --- the helpers the four above are written in ---------------------------------------------

    /** The whole authentication, driven the way the browser drives it. */
    private static void holdTheKey(final HttpClient browser, final TestAuthenticator key)
            throws Exception {
        final HttpResponse<String> started =
                post(browser, "/auth/webauthn/authenticate/start", "");
        assertEquals(200, started.statusCode(), started.body());
        final HttpResponse<String> finished =
                finishAssertion(browser, key.assertion(started.body(), ORIGIN));
        assertEquals(200, finished.statusCode(), finished.body());
    }

    private static HttpResponse<String> finishAssertion(final HttpClient browser,
                                                        final String credential) throws Exception {
        final JsonObject envelope = new JsonObject();
        envelope.addProperty("credential", credential);
        return post(browser, "/auth/webauthn/authenticate/finish", GSON.toJson(envelope));
    }

    /**
     * Moves this browser's key ceremony back out of the five-minute window.
     *
     * <p>A column and not a clock. The alternative - waiting - would put five minutes into every
     * run of this suite, and injecting a clock into the service would mean the thing under test is
     * not the thing that is deployed. The row is what the service reads, so the row is what is
     * moved.</p>
     */
    private static void heldLongAgo(final HttpClient browser) throws Exception {
        final String csrf = GSON.fromJson(get(browser, "/api/me").body(), JsonObject.class)
                .get("csrf").getAsString();
        try (var connection = data.dataSource().getConnection();
             var statement = connection.prepareStatement(
                     "UPDATE steward_session SET verified_at = now() - interval '1 hour'"
                             + " WHERE csrf = ?")) {
            statement.setString(1, csrf);
            assertEquals(1, statement.executeUpdate(), "no session matched that browser");
        }
    }

    private static HttpResponse<String> delete(final HttpClient browser, final String path)
            throws Exception {
        final JsonObject me = GSON.fromJson(get(browser, "/api/me").body(), JsonObject.class);
        return browser.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + UI_PORT + path))
                .header("X-Steward-CSRF", me.get("csrf").getAsString())
                .DELETE()
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> put(final HttpClient browser, final String path,
                                            final String body) throws Exception {
        final JsonObject me = GSON.fromJson(get(browser, "/api/me").body(), JsonObject.class);
        return browser.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + UI_PORT + path))
                .header("Content-Type", "application/json")
                .header("X-Steward-CSRF", me.get("csrf").getAsString())
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    /** The whole registration, driven the way the browser drives it. Nothing is stood in for. */
    private static void registerAKey(final HttpClient browser, final TestAuthenticator key,
                                     final String label) throws Exception {
        final HttpResponse<String> started = post(browser, "/auth/webauthn/register/start", "");
        assertEquals(200, started.statusCode(), started.body());
        final HttpResponse<String> finished =
                finishRegistration(browser, key.register(started.body(), ORIGIN), label);
        assertEquals(200, finished.statusCode(), finished.body());
    }

    /**
     * The finish, with the credential carried as a STRING inside the envelope.
     *
     * <p>That is the shape the route takes and it is not an accident: the envelope is this
     * service's JSON and Gson parses it, the credential is the library's JSON and only the library
     * may parse it. Written out here rather than hidden, because a test that quietly nested the
     * object would be testing a route that does not exist.</p>
     */
    private static HttpResponse<String> finishRegistration(final HttpClient browser,
                                                           final String credential,
                                                           final String label) throws Exception {
        final JsonObject envelope = new JsonObject();
        envelope.addProperty("label", label);
        envelope.addProperty("credential", credential);
        return post(browser, "/auth/webauthn/register/finish", GSON.toJson(envelope));
    }

    // -------------------------------------------------------------------------------------------
    // The log follow, which is the one thing in this interface that outlives its own request
    // -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("an earlier run reaches the browser as a run, not as one more line")
    void theRunEventKeepsItsName() throws Exception {
        final HttpClient browser = browser();
        signIn(browser);
        holdTheKey(browser, authenticator);
        // Over a socket, closed and then waited out: a follow still running when this class stops
        // its server spins on a recycled request and floods every class after it with warnings
        // until the heap is gone - measured, the one time this test left it behind.
        final int whileFollowing;
        try (java.net.Socket tab = openTheLogOverASocket(browser)) {
            final BufferedReader lines = new BufferedReader(
                    new InputStreamReader(tab.getInputStream(), StandardCharsets.UTF_8));
            assertTrue(waitForALineSaying(lines, "event: run"), "the run event lost its name");
            assertTrue(waitForALineSaying(lines, "Earlier run, 22 Sep 19:44"));
            assertTrue(waitForALineSaying(lines, "event: line"));
            whileFollowing = workerConnections.get();
        }
        assertTrue(theFollowsConnectionClosed(whileFollowing), "the follow outlived its socket");
    }

    @Test
    @DisplayName("a follow ends when the session does, instead of running on in a signed-out tab")
    void loggingOutEndsTheFollow() throws Exception {
        final HttpClient browser = browser();
        signIn(browser);
        // Since package C a Discord session on its own reads nothing, this log included. The
        // follow is what is under test here, not the door in front of it.
        holdTheKey(browser, authenticator);
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
            holdTheKey(browser, authenticator);
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

    /** The value of the session cookie this response set, or a failure naming what it did set. */
    private static String sessionCookieOf(final HttpResponse<String> response) {
        return response.headers().allValues("Set-Cookie").stream()
                .filter(header -> header.startsWith(Sessions.COOKIE + "="))
                .map(header -> header.substring(Sessions.COOKIE.length() + 1).split(";", 2)[0])
                .findFirst()
                .orElseGet(() -> fail("this response set no session cookie: "
                        + response.headers().map()));
    }

    /**
     * Stops the interface and starts a new one against the same database.
     *
     * <p>As close to {@code docker restart steward-ui} as a test in one JVM gets: a new
     * {@code StewardUi}, a new {@code Data} and therefore a new connection pool, sharing nothing
     * with the old one but the rows. {@code Data} is deliberately NOT closed - {@code http} and
     * every other test in this class still hold sessions in the old one, and closing a Hikari pool
     * out from under them would fail the next test rather than this one.</p>
     */
    private static void restartTheInterface() throws Exception {
        ui.stop();
        ui = new StewardUi(config,
                new DiscordAuth(config.discord(), config.publicUrl(),
                        "http://127.0.0.1:" + DISCORD_PORT),
                new InternalClient("steward-worker", config.worker().baseUrl(),
                        config.worker().token(), Duration.ofSeconds(5)),
                new InternalClient("steward-deployer", config.deployer().baseUrl(),
                        config.deployer().token(), Duration.ofSeconds(5)),
                data);
        ui.start(UI_PORT);
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
        return post(http, path, body);
    }

    private static HttpResponse<String> post(final HttpClient browser, final String path,
                                             final String body) throws Exception {
        final JsonObject me = GSON.fromJson(get(browser, "/api/me").body(), JsonObject.class);
        return browser.send(HttpRequest.newBuilder(
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
