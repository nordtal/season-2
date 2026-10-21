package eu.nordtal.season.steward;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import eu.nordtal.season.common.time.ProcessScheduler;
import eu.nordtal.season.database.DatabaseRole;
import eu.nordtal.season.database.TestDatabase;
import eu.nordtal.season.database.access.AdminTree;
import eu.nordtal.season.database.notify.SignalHub;
import eu.nordtal.season.internalapi.agent.AgentClient;
import eu.nordtal.season.internalapi.agent.AgentWire;
import eu.nordtal.season.settings.Database;
import eu.nordtal.season.settings.DatabaseSpec;
import eu.nordtal.season.steward.alert.Thresholds;
import eu.nordtal.season.steward.auth.DiscordAuth;
import eu.nordtal.season.steward.auth.SecondFactor;
import eu.nordtal.season.steward.auth.Sessions;
import eu.nordtal.season.steward.auth.TestAuthenticator;
import eu.nordtal.season.steward.config.WebSpec;
import eu.nordtal.season.steward.data.Data;
import eu.nordtal.season.steward.stack.StackApi;
import eu.nordtal.season.stewardagent.AgentStandIn;
import eu.nordtal.season.stewardagent.docker.FakeDaemon;
import eu.nordtal.season.stewardagent.plan.PluginFiles;
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
import java.util.stream.Stream;
import org.slf4j.LoggerFactory;

/**
 * Runs the real interface on this machine against a scratch database and stand-ins, signed in as an invented admin.
 *
 * Nothing reaches the live database, Discord, bunq or the Docker daemon.
 */
public final class StewardPreview {

    static final String ADMIN = "100000000000000001";

    private static final String ADMIN_NAME = "Preview";

    private static final String DUMP_OWNER = "nordtal";

    /** A one-shot that ran and a standby at rest, beside the stand-in's smp, so their pages show. */
    private static final com.google.gson.JsonObject RESTING =
            com.google.gson.JsonParser.parseString("""
                    {"migrate": {"image": "ghcr.io/nordtal/steward-agent:latest", "restart": "no"},
                     "proxy-standby": {"image": "ghcr.io/nordtal/minecraft:latest", "restart": "unless-stopped",
                       "labels": {"eu.nordtal.standby-of": "proxy"}}}
                    """).getAsJsonObject();

    private static final String PREVIEW_TOTP_KEY = previewTotpKey();

    private StewardPreview() {}

    private static String previewTotpKey() {
        final byte[] key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        return java.util.HexFormat.of().formatHex(key);
    }

    /**
     * Runs {@code [--port N] [--dump FILE] [--state FILE] [--jars DIR]} until it is stopped.
     *
     * @param args the port to serve on, a dump to restore instead of the empty schema, where the cookie goes, and a
     *     directory of {@code <service>/<jar>} whose bundles the Translations page lists
     */
    public static void main(final String[] args) throws Exception {
        final Options options = Options.of(args);
        // Deleted until this run is signed in, so a waiting reader never gets the cookie of the last run.
        Files.deleteIfExists(options.state());
        final Path scratch = Files.createTempDirectory("steward-preview");
        final TestDatabase postgres =
                options.dump() == null ? TestDatabase.fresh() : TestDatabase.restored(options.dump(), DUMP_OWNER);
        try (Database database = Database.open(asSteward(postgres), "steward-preview");
                ProcessScheduler scheduler = new ProcessScheduler(
                        "steward-preview", failure -> System.err.println("A task failed: " + failure));
                AgentStandIn agent = new AgentStandIn(
                        scratch, 0, cfg -> pluginFiles(cfg, scratch.resolve("volumes")), servicesOf(options.jars()));
                StandInDiscord discord = new StandInDiscord()) {
            furnish(agent);
            final Path jars = options.jars();
            if (jars != null) {
                copyJars(jars, agent.configs);
            }
            final Steward.Configs configs = java.util.Objects.requireNonNull(
                    Steward.configsOf(asSteward(postgres), database), "the stored settings were refused");
            final Data data = new Data(database, Clock.systemUTC());
            final AgentClient client = new AgentClient(agent.client());
            final WebSpec config = config(options.port(), discord);
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
            try (SignalHub signals = signalsOf(asSteward(postgres))) {
                web.listen(signals);
                signals.start();
                admit(data, discord);
                final String session = signIn(config.publicUrl());
                // The key counts as just held while this runs, so no change asks for it again.
                final Sessions sessions = new Sessions(data.dataSource(), Duration.ofDays(1));
                scheduler.every(
                        Duration.ZERO,
                        Duration.ofMinutes(1),
                        () -> sessions.markVerified(session, SecondFactor.StepUpLength.DEFAULT.length()));
                writeState(options.state(), session);
                System.out.println("Steward preview on " + config.publicUrl() + ", signed in as " + ADMIN_NAME
                        + " (" + ADMIN + "). Cookie " + Sessions.COOKIE + "=" + session + ", Playwright state in "
                        + options.state().toAbsolutePath() + ". Stop it to throw everything away.");
                Runtime.getRuntime().addShutdownHook(new Thread(() -> forget(options.state()), "preview-shutdown"));
                new CountDownLatch(1).await();
            } finally {
                web.stop();
            }
        }
    }

    /** Gives the stand-in agent the stopped containers and the jars that the preview shows. */
    private static void furnish(final AgentStandIn agent) throws IOException {
        agent.daemon.stopped.add(new FakeDaemon.Stopped("migrate", 0, "2026-10-06T01:00:09Z"));
        agent.daemon.stopped.add(new FakeDaemon.Stopped("proxy-standby", 143, "2026-10-06T01:20:00Z"));
        eu.nordtal.season.stewardagent.PluginJars.write(
                agent.volumes.resolve("smp/plugins/smp-0.17.0.jar"),
                "{\"id\": \"smp\", \"local\": true}",
                java.util.Map.of());
        // smp's descriptor sits where the agent reads it, so Steward draws each group with the editor smp's jar names.
        eu.nordtal.season.stewardagent.PluginJars.write(
                agent.configs.resolve("smp/smp.jar"),
                "{\"id\": \"smp\", \"editors\": {\"milestones\": \"milestones\", \"advancement-awards\": "
                        + "\"advancement-awards\", \"wheel\": \"wheel\", \"duels\": \"duels\"}}",
                java.util.Map.of());
        final Path plugins = agent.volumes.resolve("smp/plugins");
        write(plugins.resolve("CoreProtect/config.yml"), """
                # CoreProtect Config

                # If enabled, CoreProtect stores its data in MySQL instead of SQLite.
                use-mysql: false
                # The prefix of every table CoreProtect creates.
                table-prefix: co_
                # How many days the data is kept. 0 keeps it forever.
                rollback-days: 30
                worlds-ignored: [world_the_end]
                """);
        write(plugins.resolve("LuckPerms/config.yml"), """
                # The name of this server, used in contexts.
                server: smp
                storage-method: h2
                data:
                  # The address of the database server.
                  address: localhost
                  pool-settings:
                    maximum-pool-size: 10
                    minimum-idle: 10
                """);
        write(
                plugins.resolve("LuckPerms/contexts.json"),
                "{\n  \"static-contexts\": {},\n  \"default-contexts\": {}\n}\n");
        write(plugins.resolve("LuckPerms/yaml-storage/groups/default.yml"), """
                name: default
                permissions:
                - luckperms.user.info:
                    value: true
                """);
        write(plugins.resolve("LuckPerms/yaml-storage/groups/admin.yml"), "name: admin\nweight: 100\n");
        write(plugins.resolve("Chunky/config.yml"), """
                version: 2
                continue-on-restart: false
                silent: false
                update-interval: 1
                """);
        write(plugins.resolve("bStats/config.yml"), """
                # bStats collects some basic information for plugin authors.
                enabled: true
                serverUuid: 6a9b1c3e-0000-4000-8000-000000000000
                logFailedRequests: false
                """);
        write(plugins.resolve("bStats/notes.txt"), "Opened as text: no form for a .txt file.\n");
    }

    private static void write(final Path file, final String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    /** The third-party plugins on smp whose files the settings tab lists: a single file, a tree, no icon, no jar. */
    private static final List<AgentWire.Plugin> PLUGINS = List.of(
            plugin(
                    "CoreProtect",
                    "https://cdn.modrinth.com/data/Lu3KuzdV/b2c4b7b0033ab09cc166f2848003ef3a02c70a83.png"),
            plugin(
                    "LuckPerms",
                    "https://cdn.modrinth.com/data/Vebnzrzj/90943902cc650e95b167265b8f2d7c893f82c8f4_96.webp"),
            plugin("Chunky", null));

    private static AgentWire.Plugin plugin(final String name, final @org.jspecify.annotations.Nullable String icon) {
        return new AgentWire.Plugin(
                name,
                AgentWire.PluginGroup.ADDED,
                null,
                true,
                true,
                null,
                null,
                "1.0.0",
                null,
                name,
                null,
                null,
                null,
                null,
                icon,
                null);
    }

    /** Serves the plugin files of {@code volumes}, the stand-in's own, which {@link #furnish} fills. */
    private static void pluginFiles(final io.javalin.config.JavalinConfig cfg, final Path volumes) {
        final PluginFiles files =
                new PluginFiles(service -> new AgentWire.Plugins(service, "paper", "1.21.10", true, PLUGINS), volumes);
        cfg.routes.get(AgentWire.PLUGIN_FILES, files::list);
        cfg.routes.get(AgentWire.PLUGIN_FILE, files::read);
        cfg.routes.post(AgentWire.PLUGIN_FILE, files::save);
    }

    /** {@link #RESTING}, and each service of {@code jars} that the compose.yml of the stand-in does not name yet. */
    private static JsonObject servicesOf(final @org.jspecify.annotations.Nullable Path jars) throws IOException {
        final JsonObject services = RESTING.deepCopy();
        if (jars == null) {
            return services;
        }
        final JsonObject named = JsonParser.parseString(AgentStandIn.SERVICES).getAsJsonObject();
        try (Stream<Path> listed = Files.list(jars)) {
            for (final Path directory : listed.filter(Files::isDirectory).toList()) {
                final String service = directory.getFileName().toString();
                if (!named.has(service) && !services.has(service)) {
                    final JsonObject image = new JsonObject();
                    image.addProperty("image", "ghcr.io/nordtal/" + service + ":latest");
                    services.add(service, image);
                }
            }
        }
        return services;
    }

    /** Copies every {@code <service>/<jar>} of {@code jars} to where the stand-in agent reads that service's jars. */
    private static void copyJars(final Path jars, final Path configs) throws IOException {
        try (Stream<Path> listed = Files.walk(jars, 2)) {
            for (final Path jar :
                    listed.filter(path -> path.toString().endsWith(".jar")).toList()) {
                final Path into = configs.resolve(jars.relativize(jar));
                Files.createDirectories(into.getParent());
                Files.copy(jar, into);
            }
        }
    }

    private static SignalHub signalsOf(final DatabaseSpec steward) {
        return SignalHub.open(
                steward.jdbcUrl(),
                steward.username(),
                steward.password(),
                steward.queryTimeoutSeconds(),
                "steward-preview-signals",
                LoggerFactory.getLogger(StewardPreview.class));
    }

    private static void forget(final Path state) {
        try {
            Files.deleteIfExists(state);
        } catch (final IOException ignored) {
            // A cookie of a database that no longer exists opens nothing.
        }
    }

    private static StackApi stackOf(
            final Steward.Configs configs,
            final AgentClient client,
            final Database database,
            final Data data,
            final ProcessScheduler scheduler) {
        return Steward.buildStack(configs.config(), client, database, data, configs.zone(), scheduler);
    }

    /** Makes the invented admin a member of the stand-in and an admin, the root of an empty tree or else below it. */
    private static void admit(final Data data, final StandInDiscord discord) throws Exception {
        discord.memberId.set(ADMIN);
        discord.memberNick.set(ADMIN_NAME);
        discord.rootId.set(ADMIN);
        final List<AdminTree.Admin> admins = AdminTree.using(data.dataSource()).admins();
        if (!admins.isEmpty()) {
            StandInDiscord.admitBelow(
                    data.dataSource(), ADMIN, admins.getFirst().discordId().value());
        }
    }

    /** Signs in and registers a software key like a browser, and returns the value of the session cookie. */
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

    private static WebSpec config(final int port, final StandInDiscord discord) {
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
                return discord.spec();
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

            // A key that lives for this run only, so an authenticator app can be added and used here.
            @Override
            public String totpKey() {
                return PREVIEW_TOTP_KEY;
            }

            @Override
            public WebPushSpec webPush() {
                return new WebPushSpec() {};
            }
        };
    }

    private static final String USAGE = "usage: [--port N] [--dump FILE] [--state FILE] [--jars DIR]";

    private record Options(
            int port,
            @org.jspecify.annotations.Nullable Path dump,
            Path state,
            @org.jspecify.annotations.Nullable Path jars) {

        static Options of(final String[] args) {
            int port = 18180;
            Path dump = null;
            Path state = Path.of("build/preview/state.json");
            Path jars = null;
            for (int i = 0; i + 1 < args.length; i += 2) {
                switch (args[i]) {
                    case "--port" -> port = Integer.parseInt(args[i + 1]);
                    case "--dump" -> dump = Path.of(args[i + 1]);
                    case "--state" -> state = Path.of(args[i + 1]);
                    case "--jars" -> jars = Path.of(args[i + 1]);
                    default -> throw new IllegalArgumentException(USAGE);
                }
            }
            if (args.length % 2 != 0) {
                throw new IllegalArgumentException(USAGE);
            }
            return new Options(port, dump, state, jars);
        }
    }
}
