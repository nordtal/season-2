package eu.nordtal.s2.steward.web;

import com.google.gson.Gson;
import eu.nordtal.jcore.persistence.sql.Database;
import eu.nordtal.s2.common.time.Waiting;
import eu.nordtal.s2.database.TestDatabase;
import eu.nordtal.s2.internalapi.agent.AgentClient;
import eu.nordtal.s2.settings.DatabaseSpec;
import eu.nordtal.s2.steward.api.StackApi;
import eu.nordtal.s2.steward.auth.DiscordAuth;
import eu.nordtal.s2.steward.auth.TestAuthenticator;
import eu.nordtal.s2.steward.config.WebSpec;
import eu.nordtal.s2.steward.data.Data;
import eu.nordtal.s2.steward.schema.Schema;
import eu.nordtal.s2.stewardagent.AgentStandIn;
import eu.nordtal.s2.stewardagent.docker.FakeDaemon;
import io.javalin.Javalin;
import io.javalin.json.JavalinGson;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

/**
 * The stand-in Docker daemon, agent and Discord, and the real interface, stack routes and database before them.
 *
 * One Postgres container, one set of stand-ins and one {@link Web}, started once per subclass.
 */
abstract class WebFixture {

    static final int AGENT_PORT = 18092;
    static final int WEB_PORT = 18090;
    static final int DISCORD_PORT = 18093;
    static final Gson GSON = new Gson();

    static final String GUILD = "1234";

    /** Who the stand-in Discord says is signing in, settable so a test can use a 32-character nickname. */
    static final java.util.concurrent.atomic.AtomicReference<String> memberId =
            new java.util.concurrent.atomic.AtomicReference<>("1");

    static final java.util.concurrent.atomic.AtomicReference<String> memberNick =
            new java.util.concurrent.atomic.AtomicReference<>("Ally");

    /** The client secret as it arrived at the stand-in Discord, or null if it never did. */
    static final java.util.concurrent.atomic.AtomicReference<String> secretDiscordSaw =
            new java.util.concurrent.atomic.AtomicReference<>();

    /** steward-agent's real routes, over the daemon the stack routes reach through it. */
    static AgentStandIn agent;

    /** The agent's daemon, with one running {@code smp} container. */
    static FakeDaemon daemon;

    static Javalin fakeDiscord;

    /** What the stand-in agent was last asked to recreate, so a test can read it back. */
    static final java.util.List<String> recreated = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    static Web web;
    static HttpClient http;
    static TestDatabase postgres;
    static Database database;
    static Data data;

    /** Kept, so a test can build a second interface against the same database. */
    static WebSpec config;

    /** Holds the daemon's socket and the backups directory, both short paths a Unix socket can bind. */
    static Path scratch;

    @BeforeAll
    static void start() throws Exception {
        scratch = Files.createTempDirectory("steward-web");
        startAgent();
        startFakeDiscord();
        config = buildConfig();
        startDatabase();
        web = newWeb();
        web.start(WEB_PORT);
    }

    private static void startAgent() throws IOException {
        // The real agent routes behind the real guard; only the deployments are stand-ins, since they run compose.
        agent = new AgentStandIn(scratch, AGENT_PORT, cfg -> {
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
        });
        daemon = agent.daemon;
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

    private static WebSpec.DiscordSpec fakeDiscordSpec() {
        return new WebSpec.DiscordSpec() {
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

    private static WebSpec buildConfig() {
        return new WebSpec() {
            @Override
            public int port() {
                return WEB_PORT;
            }

            // The traffic light's thresholds, left at the interface's own defaults.
            @Override
            public AlertSpec alerts() {
                return new AlertSpec() {};
            }

            @Override
            public DiscordSpec discord() {
                return fakeDiscordSpec();
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
        database = Schema.open(new DatabaseSpec() {
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
        });
        data = new Data(database, Clock.systemUTC());
    }

    /**
     * The production constructors over the stand-ins: who is signed in is read out of the session, by deployed code.
     *
     * A new {@link StackApi} each time, since stopping a {@link Web} closes the one it was given.
     */
    static Web newWeb() {
        final AgentClient client =
                new AgentClient(agent.client(), Waiting.on(Clock.systemUTC()), Duration.ofSeconds(5));
        final StackApi stack = new StackApi(
                client,
                data.updates(),
                data.audit(),
                new StackApi.Nightly("04:45", List.of("MONDAY"), "05:15", List.of(), ZoneId.of("Europe/Berlin")),
                Clock.systemUTC());
        return new Web(
                config,
                new DiscordAuth(config.discord(), config.publicUrl(), "http://127.0.0.1:" + DISCORD_PORT),
                stack,
                client,
                true,
                data,
                Clock.systemUTC());
    }

    /** The one key account "1" has, registered once in {@code WebTestSupport}. */
    static final TestAuthenticator authenticator = new TestAuthenticator();

    /**
     * A season date a month ahead, the same for the whole run.
     *
     * A date in the past is refused, and so is a launch after the SMP start.
     */
    static final String SOON = java.time.Instant.now()
            .plus(Duration.ofDays(30))
            .truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
            .toString();

    /** The origin the interface expects, which is `public-url`'s and not this JVM's address. */
    static final String ORIGIN = "https://steward.dev.nordtal.eu";

    @AfterAll
    static void stop() {
        if (web != null) {
            web.stop();
        }
        if (agent != null) {
            try {
                agent.close();
            } catch (final IOException ignored) {
                // A socket file left in a temp directory is not worth failing a test run over.
            }
        }
        if (fakeDiscord != null) {
            fakeDiscord.stop();
        }
        if (database != null) {
            database.close();
        }
        deleteTree(scratch);
    }

    private static void deleteTree(final Path root) {
        if (root != null) {
            try (var walk = Files.walk(root)) {
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
