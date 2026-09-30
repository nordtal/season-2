package eu.nordtal.s2.steward.ui;

import com.google.gson.Gson;
import eu.nordtal.s2.database.TestDatabase;
import eu.nordtal.s2.settings.DatabaseSpec;
import eu.nordtal.s2.steward.ui.auth.DiscordAuth;
import eu.nordtal.s2.steward.ui.auth.TestAuthenticator;
import eu.nordtal.s2.steward.ui.config.UiSpec;
import eu.nordtal.s2.steward.ui.data.Data;
import eu.nordtal.s2.steward.ui.internal.InternalClient;
import io.javalin.Javalin;
import io.javalin.json.JavalinGson;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

/**
 * The stand-in worker, deployer and Discord, and the real interface and database in front of them.
 *
 * One Postgres container, one set of stand-ins and one {@link StewardUi}, started once per subclass.
 */
abstract class StewardUiFixture {

    static final int WORKER_PORT = 18091;
    static final int DEPLOYER_PORT = 18092;
    static final int UI_PORT = 18090;
    static final int DISCORD_PORT = 18093;
    static final Gson GSON = new Gson();

    static final String GUILD = "1234";
    static final String WORKER_TOKEN = "worker-token";

    static final AtomicBoolean workerBroken = new AtomicBoolean(false);

    /** Who the stand-in Discord says is signing in, settable so a test can use a 32-character nickname. */
    static final java.util.concurrent.atomic.AtomicReference<String> memberId =
            new java.util.concurrent.atomic.AtomicReference<>("1");

    static final java.util.concurrent.atomic.AtomicReference<String> memberNick =
            new java.util.concurrent.atomic.AtomicReference<>("Ally");

    /** The client secret as it arrived at the stand-in Discord, or null if it never did. */
    static final java.util.concurrent.atomic.AtomicReference<String> secretDiscordSaw =
            new java.util.concurrent.atomic.AtomicReference<>();

    /**
     * Whether the stand-in worker's log keeps producing lines after its first one.
     *
     * A quiet log still sends the worker's heartbeat comments, and is what tells a closed tab from a lost session.
     */
    static final AtomicBoolean chattyLog = new AtomicBoolean(true);

    /** Whoever is following a log through the stand-in worker right now. */
    static final List<io.javalin.http.sse.SseClient> workerFollowers =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
     * How many connections are open to the stand-in worker, counted by Jetty rather than by SSE clients.
     *
     * An SSE client is only removed when a write to it fails, so a quiet stand-in never sees a hangup.
     */
    static final java.util.concurrent.atomic.AtomicInteger workerConnections =
            new java.util.concurrent.atomic.AtomicInteger();

    static Javalin fakeWorker;
    static Javalin fakeDeployer;
    static Javalin fakeDiscord;

    /** What the stand-in deployer was last asked to recreate, so a test can read it back. */
    static final java.util.List<String> recreated = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    static StewardUi ui;
    static HttpClient http;
    static TestDatabase postgres;
    static Data data;

    /** Kept, so a test can build a second interface against the same database. */
    static UiSpec config;

    static Path configRoot;

    @BeforeAll
    static void start() throws Exception {
        writeConfigFixtures();
        startFakeWorker();
        startFakeDeployer();
        startFakeDiscord();
        config = buildConfig();
        startDatabase();
        startUi();
    }

    private static void writeConfigFixtures() throws Exception {
        // A stand-in for the config volumes: one directory per service, one with a file inside a data directory.
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
    }

    private static org.eclipse.jetty.server.ServerConnector countingWorkerConnector(
            final org.eclipse.jetty.server.Server server,
            final org.eclipse.jetty.server.HttpConfiguration httpConfiguration) {
        final org.eclipse.jetty.server.ServerConnector counted = new org.eclipse.jetty.server.ServerConnector(
                server, new org.eclipse.jetty.server.HttpConnectionFactory(httpConfiguration));
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
    }

    /** A log that never ends, which is what a running container's is. */
    private static void followWorkerLog(final io.javalin.http.sse.SseClient client) {
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
                    // The real worker's heartbeat, only faster, so this test does not wait ten seconds.
                    client.sendComment("following " + client.ctx().pathParam("name"));
                }
            }
        });
    }

    private static void startFakeWorker() {
        fakeWorker = Javalin.create(cfg -> {
                    cfg.jsonMapper(new JavalinGson(new Gson(), true));
                    cfg.startup.showJavalinBanner = false;
                    cfg.jetty.addConnector(
                            (server, httpConfiguration) -> countingWorkerConnector(server, httpConfiguration));
                    // The same door the real worker has: health open, everything else behind the shared secret.
                    cfg.routes.before("/api/*", ctx -> {
                        if (!ctx.path().equals("/api/health") && !WORKER_TOKEN.equals(ctx.header("X-Steward-Token"))) {
                            throw new io.javalin.http.UnauthorizedResponse("bad or missing token");
                        }
                    });
                    cfg.routes.get("/api/health", ctx -> ctx.json(Map.of("status", "ok")));

                    // The real ConfigApi, not a stand-in, against a real directory of real files.
                    final eu.nordtal.s2.steward.worker.api.ConfigApi configApi =
                            new eu.nordtal.s2.steward.worker.api.ConfigApi(
                                    configRoot, service -> java.util.Optional.empty());
                    cfg.routes.get("/api/config", configApi::list);
                    cfg.routes.get("/api/config/<file>", configApi::one);
                    cfg.routes.put("/api/config/<file>", configApi::save);
                    cfg.routes.sse("/api/services/{name}/logs", StewardUiFixture::followWorkerLog);
                    cfg.routes.get("/api/services", ctx -> {
                        if (workerBroken.get()) {
                            ctx.status(502).result("the daemon is not answering");
                            return;
                        }
                        ctx.json(List.of(Map.of(
                                "service", "smp", "state", "running", "hasConsole", true, "drift", "UP_TO_DATE")));
                    });
                    cfg.routes.post(
                            "/api/services/{name}/console",
                            ctx -> ctx.status(202).json(Map.of("sent", "list")));
                })
                .start(WORKER_PORT + 100);
    }

    private static void startFakeDeployer() {
        // A stand-in for steward-deployer: a second process and a second secret, so a second stub too.
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
                    cfg.routes.get("/api/services", ctx -> ctx.json(Map.of("smp", "ghcr.io/nordtal/minecraft:latest")));
                    cfg.routes.post("/api/recreate/{service}", ctx -> {
                        recreated.add(ctx.pathParam("service"));
                        ctx.status(202)
                                .json(Map.of(
                                        "id",
                                        "job-1",
                                        "kind",
                                        "recreate",
                                        "services",
                                        List.of(ctx.pathParam("service")),
                                        "state",
                                        "RUNNING"));
                    });
                    cfg.routes.get(
                            "/api/jobs/{id}",
                            ctx -> ctx.json(Map.of(
                                    "id",
                                    ctx.pathParam("id"),
                                    "kind",
                                    "recreate",
                                    "state",
                                    "DONE",
                                    "exitCode",
                                    0,
                                    "lines",
                                    List.of("Container nordtal-s2-smp-1  Recreated"))));
                })
                .start(DEPLOYER_PORT);
    }

    private static void startFakeDiscord() {
        // The one system boundary in the sign-in; everything else in the flow is the interface's own code.
        fakeDiscord = Javalin.create(cfg -> {
                    cfg.jsonMapper(new JavalinGson(new Gson(), true));
                    cfg.startup.showJavalinBanner = false;
                    cfg.routes.post("/oauth2/token", ctx -> {
                        final Map<String, String> form = new java.util.LinkedHashMap<>();
                        final String raw = ctx.body();
                        int cursor = 0;
                        while (cursor <= raw.length()) {
                            final int amp = raw.indexOf('&', cursor);
                            final String pair = amp == -1 ? raw.substring(cursor) : raw.substring(cursor, amp);
                            final int equals = pair.indexOf('=');
                            form.put(
                                    pair.substring(0, equals),
                                    java.net.URLDecoder.decode(
                                            pair.substring(equals + 1), java.nio.charset.StandardCharsets.UTF_8));
                            if (amp == -1) {
                                break;
                            }
                            cursor = amp + 1;
                        }
                        secretDiscordSaw.set(form.get("client_secret"));
                        if (!"the-code".equals(form.get("code"))) {
                            ctx.status(400).json(Map.of("error", "invalid_grant"));
                            return;
                        }
                        ctx.json(Map.of("access_token", "an-access-token", "token_type", "Bearer"));
                    });
                    cfg.routes.get("/users/@me", ctx -> ctx.json(Map.of("id", memberId.get(), "username", "ally")));
                    cfg.routes.get("/users/@me/guilds/{guild}/member", ctx -> {
                        if (!GUILD.equals(ctx.pathParam("guild"))) {
                            ctx.status(404).json(Map.of("message", "Unknown Guild"));
                            return;
                        }
                        ctx.json(Map.of("nick", memberNick.get(), "roles", List.of("9999")));
                    });
                })
                .start(DISCORD_PORT);
    }

    private static UiSpec.WorkerSpec fakeWorkerSpec() {
        return new UiSpec.WorkerSpec() {
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

    private static UiSpec.DiscordSpec fakeDiscordSpec() {
        return new UiSpec.DiscordSpec() {
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
        };
    }

    private static UiSpec.DeployerSpec fakeDeployerSpec() {
        return new UiSpec.DeployerSpec() {
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

    private static UiSpec buildConfig() {
        return new UiSpec() {
            @Override
            public int port() {
                return UI_PORT;
            }

            // The traffic light's thresholds, left at the interface's own defaults.
            @Override
            public AlertSpec alerts() {
                return new AlertSpec() {};
            }

            @Override
            public WorkerSpec worker() {
                return fakeWorkerSpec();
            }

            @Override
            public DiscordSpec discord() {
                return fakeDiscordSpec();
            }

            @Override
            public DeployerSpec deployer() {
                return fakeDeployerSpec();
            }

            @Override
            public WebAuthnSpec webauthn() {
                // The defaults, deliberately: relying-party-id and public-url are the production pair.
                return new WebAuthnSpec() {};
            }

            @Override
            public AvatarSpec avatars() {
                return new AvatarSpec() {};
            }

            @Override
            public WebPushSpec webPush() {
                // The defaults: both keys blank, meaning "not configured".
                return new WebPushSpec() {};
            }
        };
    }

    private static void startDatabase() {
        // A real database with the real migrations, not a stub that only agrees with itself.
        postgres = TestDatabase.fresh();
        data = new Data(
                new DatabaseSpec() {
                    @Override
                    public String jdbcUrl() {
                        return postgres.jdbcUrl();
                    }

                    @Override
                    public String username() {
                        return postgres.username();
                    }

                    @Override
                    public String password() {
                        return postgres.password();
                    }
                },
                Clock.systemUTC());
    }

    private static void startUi() throws Exception {
        // The production constructor: who is signed in is read out of the session, by deployed code.
        ui = new StewardUi(
                config,
                new DiscordAuth(config.discord(), config.publicUrl(), "http://127.0.0.1:" + DISCORD_PORT),
                new InternalClient(
                        "steward-worker",
                        config.worker().baseUrl(),
                        config.worker().token(),
                        Duration.ofSeconds(5)),
                new InternalClient(
                        "steward-deployer",
                        config.deployer().baseUrl(),
                        config.deployer().token(),
                        Duration.ofSeconds(5)),
                data,
                Clock.systemUTC());
        ui.start(UI_PORT);
    }

    /** The one key account "1" has, registered once in {@code StewardUiTestSupport}. */
    static final TestAuthenticator authenticator = new TestAuthenticator();

    /** The origin the interface expects, which is `public-url`'s and not this JVM's address. */
    static final String ORIGIN = "https://steward.dev.nordtal.eu";

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
}
