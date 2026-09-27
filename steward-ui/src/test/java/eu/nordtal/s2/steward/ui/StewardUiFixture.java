package eu.nordtal.s2.steward.ui;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.gson.Gson;
import eu.nordtal.s2.steward.ui.auth.DiscordAuth;
import eu.nordtal.s2.steward.ui.auth.TestAuthenticator;
import eu.nordtal.s2.steward.ui.config.DatabaseSpec;
import eu.nordtal.s2.steward.ui.config.UiSpec;
import eu.nordtal.s2.steward.ui.data.Data;
import eu.nordtal.s2.steward.ui.internal.InternalClient;
import io.javalin.Javalin;
import io.javalin.json.JavalinGson;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * The stand-in worker, deployer and Discord, and the real interface and database in front of them.
 *
 * Shared by every steward-ui integration test class: one Postgres container, one set of fake
 * Javalin servers and one running {@link StewardUi}, started once per subclass and torn down after.
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

    /**
     * Who the stand-in Discord says is signing in.
     *
     * Changeable, because the default is the shape of the bug.
     *
     * "Ally" and {@code "1"} are eight characters together, and every assertion about the
     * journal in this class was written against them. A real snowflake is 17 to 19 digits and a
     * guild nickname may be 32 characters, so the composed {@code "name (id)"} this interface used
     * to write into {@code audit_log.actor} - {@code varchar(32)} - overflowed for anything but a
     * very short name, and no test here could see it. Making the pair settable is the cheapest
     * honest fix: one sign-in against the real flow, rather than a second copy of this whole
     * fixture with different constants in it.
     */
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
     * A quiet log is not an edge case, it is the normal one: a healthy Minecraft server says
     * nothing for minutes at a time. It is also the only way to tell two mechanisms apart - a
     * follow that ends because the next line found the session gone, and one that ends because
     * somebody closed the tab. With a chatty log the first hides the second.
     *
     * Quiet means no {@code data:} lines - no log output. The connection still carries a
     * comment now and then, because that is what {@code steward-worker} does: it has the same
     * heartbeat, for the same two reasons, and a stand-in that went completely silent would be
     * testing the interface against a worker that does not exist.
     */
    static final AtomicBoolean chattyLog = new AtomicBoolean(true);

    /** Whoever is following a log through the stand-in worker right now. */
    static final List<io.javalin.http.sse.SseClient> workerFollowers =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
     * How many connections are open to the stand-in worker, counted by Jetty itself.
     *
     * Counting SSE clients instead was the obvious thing and it is wrong: a Javalin SSE client
     * is removed when a write to it fails, so a stand-in worker with nothing to say never
     * notices the interface hanging up - it has the same blindness the interface has, and a blind
     * instrument cannot measure whether somebody else can see. The socket is not blind.
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
    static PostgreSQLContainer<?> postgres;
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
                            new eu.nordtal.s2.steward.worker.api.ConfigApi(configRoot, (service, command) -> {});
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
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "no docker daemon - skipping");
        postgres = new PostgreSQLContainer<>("postgres:17-alpine");
        postgres.start();
        Flyway.configure(StewardUiFixture.class.getClassLoader())
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
                data);
        ui.start(UI_PORT);
    }

    /** The one key account "1" has. Registered once, in {@code StewardUiTestSupport}, and used by everything. */
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
}
