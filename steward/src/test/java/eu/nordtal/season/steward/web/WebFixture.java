package eu.nordtal.season.steward.web;

import com.google.gson.Gson;
import eu.nordtal.season.common.time.TestScheduler;
import eu.nordtal.season.database.DatabaseRole;
import eu.nordtal.season.database.TestDatabase;
import eu.nordtal.season.internalapi.agent.AgentClient;
import eu.nordtal.season.settings.Database;
import eu.nordtal.season.settings.DatabaseSpec;
import eu.nordtal.season.settings.network.NetworkSettings;
import eu.nordtal.season.steward.alert.Thresholds;
import eu.nordtal.season.steward.api.StackApi;
import eu.nordtal.season.steward.auth.DiscordAuth;
import eu.nordtal.season.steward.auth.TestAuthenticator;
import eu.nordtal.season.steward.config.WebSpec;
import eu.nordtal.season.steward.data.Data;
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
 * The stand-in Docker daemon, agent and Discord, and the real interface, stack routes and database before them.
 *
 * One Postgres container, one set of stand-ins and one {@link Web}, started once per subclass.
 */
abstract class WebFixture {

    static final int AGENT_PORT = 18092;
    static final int WEB_PORT = 18090;
    static final int DISCORD_PORT = 18093;
    static final Gson GSON = new Gson();

    static final String GUILD = StandInDiscord.GUILD;

    /** Discord's sign-in routes, answering for whom {@link #memberId} names. */
    static final StandInDiscord fakeDiscord = new StandInDiscord();

    /** Who the stand-in Discord says is signing in, settable so a test can use a 32-character nickname. */
    static final java.util.concurrent.atomic.AtomicReference<String> memberId = fakeDiscord.memberId;

    static final java.util.concurrent.atomic.AtomicReference<String> memberNick = fakeDiscord.memberNick;

    /** The client secret as it arrived at the stand-in Discord, or null if it never did. */
    static final java.util.concurrent.atomic.AtomicReference<String> secretDiscordSaw = fakeDiscord.secretSeen;

    /** steward-agent's real routes, over the daemon the stack routes reach through it. */
    static AgentStandIn agent;

    /** The agent's daemon, with one running {@code smp} container. */
    static FakeDaemon daemon;

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
        fakeDiscord.start(DISCORD_PORT);
        config = buildConfig();
        startDatabase();
        web = newWeb();
        web.start(WEB_PORT);
    }

    private static void startAgent() throws IOException {
        // The real agent routes behind the real guard.
        agent = new AgentStandIn(scratch, AGENT_PORT, cfg -> {});
        daemon = agent.daemon;
    }

    private static WebSpec buildConfig() {
        return new WebSpec() {
            @Override
            public int port() {
                return WEB_PORT;
            }

            @Override
            public DiscordSpec discord() {
                return fakeDiscord.spec();
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
        database = Database.open(
                new DatabaseSpec() {
                    @Override
                    public String jdbcUrl() {
                        return postgres.jdbcUrl();
                    }

                    // The role steward logs in as, so a statement it was never granted fails here first.
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
     * The production constructors over the stand-ins: who is signed in is read out of the session, by deployed code.
     *
     * A new {@link StackApi} each time, since stopping a {@link Web} closes the one it was given.
     */
    static Web newWeb() {
        return newWeb(data, new AgentClient(agent.client()));
    }

    /** The same interface over another database and another agent, for a test that breaks one of them. */
    static Web newWeb(final Data over, final AgentClient client) {
        final StackApi stack = new StackApi(
                client,
                over.updates(),
                over.audit(),
                new StackApi.Nightly("04:45", List.of("MONDAY"), "05:15", List.of(), ZoneId.of("Europe/Berlin")),
                Clock.systemUTC(),
                TestScheduler.SHARED);
        return new Web(
                config,
                () -> new Thresholds(85, 90, 36),
                new DiscordAuth(config.discord(), config.publicUrl(), "http://127.0.0.1:" + DISCORD_PORT),
                stack,
                client,
                true,
                over,
                NetworkSettings.defaultLanguages(),
                Clock.systemUTC(),
                TestScheduler.SHARED);
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
                        // A leftover temp directory is not worth failing a test run over.
                    }
                });
            } catch (final IOException ignored) {
                // Same.
            }
        }
    }
}
