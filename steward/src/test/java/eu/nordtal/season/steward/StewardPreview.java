package eu.nordtal.season.steward;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import eu.nordtal.season.common.time.ProcessScheduler;
import eu.nordtal.season.database.DatabaseRole;
import eu.nordtal.season.database.TestDatabase;
import eu.nordtal.season.database.access.AdminTree;
import eu.nordtal.season.internalapi.agent.AgentClient;
import eu.nordtal.season.settings.Database;
import eu.nordtal.season.settings.DatabaseSpec;
import eu.nordtal.season.steward.alert.Thresholds;
import eu.nordtal.season.steward.api.StackApi;
import eu.nordtal.season.steward.auth.DiscordAuth;
import eu.nordtal.season.steward.auth.Sessions;
import eu.nordtal.season.steward.auth.TestAuthenticator;
import eu.nordtal.season.steward.config.WebSpec;
import eu.nordtal.season.steward.data.Data;
import eu.nordtal.season.steward.web.StandInDiscord;
import eu.nordtal.season.steward.web.Web;
import eu.nordtal.season.stewardagent.AgentStandIn;
import java.io.IOException;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The real interface on this machine, against a scratch database and stand-ins, signed in as an invented admin.
 *
 * For looking at pages: nothing reaches the live database, Discord, bunq or the Docker daemon.
 */
public final class StewardPreview {

    /** The invented admin the preview signs in as; no Discord account has this id. */
    static final String ADMIN = "100000000000000001";

    private static final String ADMIN_NAME = "Preview";

    /** Who owns the objects of a dump taken on a deployment, as compose.yml names the database user. */
    private static final String DUMP_OWNER = "nordtal";

    private StewardPreview() {}

    /**
     * {@code [--port N] [--dump FILE] [--state FILE]}; runs until it is stopped.
     *
     * @param args the port to serve on, a dump to restore instead of the empty schema, and where the cookie goes
     */
    public static void main(final String[] args) throws Exception {
        final Options options = Options.of(args);
        // Gone until this run is signed in, so whoever waits for the file never reads the last run's cookie.
        Files.deleteIfExists(options.state());
        final Path scratch = Files.createTempDirectory("steward-preview");
        final TestDatabase postgres =
                options.dump() == null ? TestDatabase.fresh() : TestDatabase.restored(options.dump(), DUMP_OWNER);
        try (Database database = Database.open(asSteward(postgres), "steward-preview");
                ProcessScheduler scheduler = new ProcessScheduler(
                        "steward-preview", failure -> System.err.println("A task failed: " + failure));
                AgentStandIn agent = new AgentStandIn(scratch, 0, cfg -> {});
                StandInDiscord discord = new StandInDiscord()) {
            final Steward.Configs configs = java.util.Objects.requireNonNull(
                    Steward.configsOf(asSteward(postgres), database), "the stored settings were refused");
            final Data data = new Data(database, Clock.systemUTC());
            final AgentClient client = new AgentClient(agent.client());
            final WebSpec config = config(options.port());
            final String discordBase = discord.start(0);
            final Web web = new Web(
                    config,
                    () -> new Thresholds(85, 90, 36),
                    new DiscordAuth(config.discord(), config.publicUrl(), discordBase),
                    stackOf(configs, client, database, data, scheduler),
                    client,
                    true,
                    data,
                    configs.languages(),
                    Clock.systemUTC(),
                    scheduler);
            web.start(options.port());
            try {
                admit(data, discord);
                final String session = signIn(config.publicUrl());
                // The key counts as just held for as long as this runs, so no change asks for it again.
                final Sessions sessions = new Sessions(data.dataSource(), Duration.ofDays(1));
                scheduler.every(Duration.ZERO, Duration.ofMinutes(1), () -> sessions.markVerified(session));
                writeState(options.state(), session);
                System.out.println("Steward preview on " + config.publicUrl() + ", signed in as " + ADMIN_NAME
                        + " (" + ADMIN + "). Cookie " + Sessions.COOKIE + "=" + session + ", Playwright state in "
                        + options.state().toAbsolutePath() + ". Stop it to throw everything away.");
                Runtime.getRuntime().addShutdownHook(new Thread(() -> forget(options.state()), "preview-shutdown"));
                // Until the process is stopped.
                new CountDownLatch(1).await();
            } finally {
                web.stop();
            }
        }
    }

    /** Deletes the cookie file as the process ends, since the JVM does not wait for {@code main} to unwind. */
    private static void forget(final Path state) {
        try {
            Files.deleteIfExists(state);
        } catch (final IOException ignored) {
            // A cookie of a database that no longer exists opens nothing.
        }
    }

    /** The stack routes as {@code serve} builds them, over the stand-in agent. */
    private static StackApi stackOf(
            final Steward.Configs configs,
            final AgentClient client,
            final Database database,
            final Data data,
            final ProcessScheduler scheduler) {
        return Steward.buildStack(configs.config(), client, database, data, configs.zone(), scheduler);
    }

    /** Makes the invented admin the stand-in's member, and an admin: the root of an empty tree, else below the root. */
    private static void admit(final Data data, final StandInDiscord discord) throws Exception {
        discord.memberId.set(ADMIN);
        discord.memberNick.set(ADMIN_NAME);
        final List<AdminTree.Admin> admins = AdminTree.using(data.dataSource()).admins();
        if (!admins.isEmpty()) {
            StandInDiscord.admitBelow(
                    data.dataSource(), ADMIN, admins.getFirst().discordId().value());
        }
    }

    /** Signs in and registers a software key the way a browser does, and returns the session cookie's value. */
    private static String signIn(final String base) throws Exception {
        final CookieManager cookies = new CookieManager();
        final HttpClient browser =
                HttpClient.newBuilder().cookieHandler(cookies).build();
        final HttpResponse<String> login = send(browser, get(base + "/auth/login"));
        final Matcher state = Pattern.compile("[?&]state=([^&]+)")
                .matcher(login.headers().firstValue("Location").orElse(""));
        if (!state.find()) {
            throw new IllegalStateException("the sign-in did not send the browser to Discord: " + login.body());
        }
        expect(
                302,
                send(browser, get(base + "/auth/callback?code=" + StandInDiscord.CODE + "&state=" + state.group(1))));
        final TestAuthenticator key = new TestAuthenticator();
        final String options = expect(200, send(browser, post(browser, base, "/auth/webauthn/register/start", "")))
                .body();
        final JsonObject finish = new JsonObject();
        finish.addProperty("label", "Preview key");
        finish.addProperty("credential", key.register(options, base));
        expect(200, send(browser, post(browser, base, "/auth/webauthn/register/finish", finish.toString())));
        return cookies.getCookieStore().getCookies().stream()
                .filter(cookie -> cookie.getName().equals(Sessions.COOKIE))
                .map(HttpCookie::getValue)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("the sign-in set no session cookie"));
    }

    private static HttpRequest get(final String url) {
        return HttpRequest.newBuilder(URI.create(url)).GET().build();
    }

    private static HttpRequest post(final HttpClient browser, final String base, final String path, final String body)
            throws Exception {
        final String csrf = JsonParser.parseString(
                        send(browser, get(base + "/api/me")).body())
                .getAsJsonObject()
                .get("csrf")
                .getAsString();
        return HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .header("X-Steward-CSRF", csrf)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private static HttpResponse<String> send(final HttpClient browser, final HttpRequest request) throws Exception {
        return browser.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> expect(final int status, final HttpResponse<String> response) {
        if (response.statusCode() != status) {
            throw new IllegalStateException(
                    response.request().uri() + " answered " + response.statusCode() + ": " + response.body());
        }
        return response;
    }

    /** Writes the cookie as Playwright's {@code storageState}, which a browser context loads as it is. */
    private static void writeState(final Path state, final String session) throws IOException {
        final JsonObject cookie = new JsonObject();
        cookie.addProperty("name", Sessions.COOKIE);
        cookie.addProperty("value", session);
        cookie.addProperty("domain", "localhost");
        cookie.addProperty("path", "/");
        cookie.addProperty("expires", -1);
        cookie.addProperty("httpOnly", true);
        cookie.addProperty("secure", false);
        cookie.addProperty("sameSite", "Lax");
        final JsonArray cookies = new JsonArray();
        cookies.add(cookie);
        final JsonObject root = new JsonObject();
        root.add("cookies", cookies);
        root.add("origins", new JsonArray());
        Files.createDirectories(state.toAbsolutePath().getParent());
        Files.writeString(state, root.toString());
    }

    /** The scratch database as steward logs in to it, under its own role. */
    private static DatabaseSpec asSteward(final TestDatabase postgres) {
        return new DatabaseSpec() {
            @Override
            public String jdbcUrl() {
                return postgres.jdbcUrl();
            }

            @Override
            public String username() {
                return DatabaseRole.STEWARD.roleName();
            }

            @Override
            public String password() {
                return DatabaseRole.STEWARD.key();
            }
        };
    }

    /** The {@code web} group for {@code http://localhost:<port>}, with the stand-in Discord and no Web Push. */
    private static WebSpec config(final int port) {
        return new WebSpec() {
            @Override
            public int port() {
                return port;
            }

            @Override
            public String publicUrl() {
                return "http://localhost:" + port;
            }

            @Override
            public DiscordSpec discord() {
                return StandInDiscord.spec();
            }

            @Override
            public WebAuthnSpec webauthn() {
                return new WebAuthnSpec() {
                    @Override
                    public String relyingPartyId() {
                        return "localhost";
                    }
                };
            }

            @Override
            public AvatarSpec avatars() {
                return new AvatarSpec() {};
            }

            @Override
            public WebPushSpec webPush() {
                return new WebPushSpec() {};
            }
        };
    }

    /** What the command line asked for. */
    private record Options(
            int port, @org.jspecify.annotations.Nullable Path dump, Path state) {

        static Options of(final String[] args) {
            int port = 18180;
            Path dump = null;
            Path state = Path.of("build/preview/state.json");
            for (int i = 0; i + 1 < args.length; i += 2) {
                switch (args[i]) {
                    case "--port" -> port = Integer.parseInt(args[i + 1]);
                    case "--dump" -> dump = Path.of(args[i + 1]);
                    case "--state" -> state = Path.of(args[i + 1]);
                    default -> throw new IllegalArgumentException("usage: [--port N] [--dump FILE] [--state FILE]");
                }
            }
            if (args.length % 2 != 0) {
                throw new IllegalArgumentException("usage: [--port N] [--dump FILE] [--state FILE]");
            }
            return new Options(port, dump, state);
        }
    }
}
