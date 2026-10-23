package eu.nordtal.season.steward;

import com.google.gson.Gson;
import eu.nordtal.season.common.time.TestScheduler;
import eu.nordtal.season.database.DatabaseRole;
import eu.nordtal.season.database.TestDatabase;
import eu.nordtal.season.internalapi.agent.AgentClient;
import eu.nordtal.season.settings.Database;
import eu.nordtal.season.settings.DatabaseSpec;
import eu.nordtal.season.settings.network.NetworkSettings;
import eu.nordtal.season.steward.alert.Thresholds;
import eu.nordtal.season.steward.auth.DiscordAuth;
import eu.nordtal.season.steward.auth.TestAuthenticator;
import eu.nordtal.season.steward.config.WebSpec;
import eu.nordtal.season.steward.data.Data;
import eu.nordtal.season.steward.stack.StackApi;
import eu.nordtal.season.stewardagent.AgentStandIn;
import eu.nordtal.season.stewardagent.docker.FakeDaemon;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

/**
 * The stand-in Docker daemon, agent and Discord, with the real interface, stack routes and database.
 *
 * One Postgres container, one set of stand-ins and one {@link Web} start once per subclass.
 */
abstract class WebFixture {

    static final Gson GSON = new Gson();

    static final String GUILD = StandInDiscord.GUILD;

    static final StandInDiscord fakeDiscord = new StandInDiscord();

    /** Who the stand-in Discord says is signing in. A test can set it to use a 32-character nickname. */
    static final java.util.concurrent.atomic.AtomicReference<String> memberId = fakeDiscord.memberId;

    static final java.util.concurrent.atomic.AtomicReference<String> memberNick = fakeDiscord.memberNick;

    static final java.util.concurrent.atomic.AtomicReference<String> secretDiscordSaw = fakeDiscord.secretSeen;

    /** The real routes of steward-agent, over the daemon that the stack routes reach through it. */
    static AgentStandIn agent;

    static FakeDaemon daemon;

    static int webPort;

    static Web web;
    static HttpClient http;
    static TestDatabase postgres;
    static Database database;
    static Data data;

    static WebSpec config;

    static String discordBase;

    /** Holds the daemon's socket and the backups directory, both short paths that a Unix socket can bind. */
    static Path scratch;

    @BeforeAll
    static void start() throws Exception {
        scratch = Files.createTempDirectory("steward-web");
        startAgent();
        discordBase = fakeDiscord.start(0);
        config = buildConfig();
        startDatabase();
        web = newWeb();
        webPort = web.start(0).port();
    }

    private static void startAgent() throws IOException {
        agent = new AgentStandIn(scratch, 0, cfg -> {});
        daemon = agent.daemon;
    }

    private static WebSpec buildConfig() {
        return new WebSpec() {
            @Override
            public DiscordSpec discord() {
                return fakeDiscord.spec();
            }

            @Override
            public WebAuthnSpec webauthn() {
                // The defaults, on purpose: relying-party-id and public-url are the production pair.
                return new WebAuthnSpec() {};
            }

            @Override
            public AvatarSpec avatars() {
                return new AvatarSpec() {};
            }

            @Override
            public WebPushSpec webPush() {
                return new WebPushSpec() {};
            }

            @Override
            public String totpKey() {
                // A fixed key, so the authenticator app is offered. Only these tests seal with it.
                return "0123456789abcdef".repeat(4);
            }
        };
    }

    private static void startDatabase() {
        postgres = TestDatabase.fresh();
        database = Database.open(
                new DatabaseSpec() {
                    @Override
                    public String jdbcUrl() {
                        return postgres.jdbcUrl();
                    }

                    // The role steward logs in as, so a statement without a grant fails here first.
                    @Override
                    public String username() {
                        return DatabaseRole.STEWARD.roleName();
                    }

                    @Override
                    public String password() {
                        return DatabaseRole.STEWARD.key();
                    }
                },
                "steward-test");
        data = new Data(database, Clock.systemUTC());
    }

    /**
     * Builds the production constructors over the stand-ins. Deployed code reads who is signed in from the session.
     *
     * Each call creates a new {@link StackApi}, because stopping a {@link Web} closes the one it got.
     */
    static Web newWeb() {
        return newWeb(data, new AgentClient(agent.client()));
    }

    static Web newWeb(final Data over, final AgentClient client) {
        final StackApi stack = new StackApi(
                client,
                over.updates(),
                new StackApi.Nightly("04:45", List.of("MONDAY"), "05:15", List.of(), ZoneId.of("Europe/Berlin")),
                Clock.systemUTC(),
                TestScheduler.SHARED);
        return new Web(
                config,
                () -> new Thresholds(85, 90, 36),
                new DiscordAuth(config.discord(), config.publicUrl(), discordBase),
                stack,
                client,
                true,
                over,
                NetworkSettings.defaultLanguages(),
                Clock.systemUTC(),
                TestScheduler.SHARED);
    }

    static final TestAuthenticator authenticator = new TestAuthenticator();

    /**
     * A season date a month ahead, the same for the whole run.
     *
     * A past date is refused, and so is a launch after the SMP start.
     */
    static final String SOON = java.time.Instant.now()
            .plus(Duration.ofDays(30))
            .truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
            .toString();

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
                // Cleanup failures do not fail a test run.
            }
        }
        fakeDiscord.close();
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
                        // Cleanup failures do not fail a test run.
                    }
                });
            } catch (final IOException ignored) {
                // Cleanup failures do not fail a test run.
            }
        }
    }
}
