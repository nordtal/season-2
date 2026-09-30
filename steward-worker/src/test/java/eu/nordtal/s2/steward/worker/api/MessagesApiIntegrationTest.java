package eu.nordtal.s2.steward.worker.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import eu.nordtal.s2.common.time.Waiting;
import eu.nordtal.s2.database.Actor;
import eu.nordtal.s2.database.TestDatabase;
import eu.nordtal.s2.database.inbox.BotRequest;
import eu.nordtal.s2.database.inbox.Inbox;
import eu.nordtal.s2.database.inbox.Outcome;
import eu.nordtal.s2.database.inbox.Request;
import eu.nordtal.s2.steward.worker.docker.DockerException;
import io.javalin.Javalin;
import io.javalin.json.JavalinGson;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link MessagesApi} served over real HTTP, the contract steward-ui reads. */
class MessagesApiIntegrationTest {

    private static final Gson GSON = new Gson();

    @TempDir
    Path configs;

    @TempDir
    Path volumes;

    private Javalin app;
    private HttpClient http;
    private int port;
    private static Inbox<BotRequest> inbox;

    /** Every request the bot was asked, in order. */
    private final List<Request<BotRequest>> asked = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** What the bot answers, or {@code null} for a bot that is not running. */
    private volatile @Nullable Function<Request<BotRequest>, Outcome> bot;

    private @Nullable ScheduledExecutorService botThread;
    /** Every console line sent, as {@code service: command}; {@link #consoleDown} makes it throw. */
    private final List<String> console = new ArrayList<>();

    private boolean consoleDown;

    @org.junit.jupiter.api.BeforeAll
    static void database() {
        inbox = Inbox.over(TestDatabase.fresh().dataSource(), BotRequest.TABLE);
    }

    @BeforeEach
    void start() {
        // A bot that claims every few milliseconds; the requests are settled as the test decided.
        botThread = Executors.newSingleThreadScheduledExecutor();
        final var _ = botThread.scheduleWithFixedDelay(
                () -> {
                    final var answer = bot;
                    if (answer != null) {
                        inbox.drain(request -> {
                            asked.add(request);
                            return answer.apply(request);
                        });
                    }
                },
                0,
                20,
                TimeUnit.MILLISECONDS);
        final MessagesApi messages = new MessagesApi(
                configs,
                volumes,
                inbox,
                (service, command) -> {
                    if (consoleDown) {
                        throw new DockerException("no running container for " + service);
                    }
                    console.add(service + ": " + command);
                },
                Waiting.on(Clock.systemUTC()));
        app = Javalin.create(config -> {
                    config.jsonMapper(new JavalinGson(new Gson(), true));
                    config.routes.get("/api/messages", messages::list);
                    config.routes.get("/api/messages/<bundle>", messages::one);
                    config.routes.put("/api/messages/<bundle>", messages::save);
                })
                .start(0);
        port = app.port();
        http = HttpClient.newHttpClient();
    }

    @AfterEach
    void stop() {
        if (app != null) {
            app.stop();
        }
        if (botThread != null) {
            botThread.shutdownNow();
        }
    }

    @Test
    void anEmptyMountListsNoBundleAtAllTheStateBeforeThisRouteExisted() throws Exception {
        final JsonArray list = GSON.fromJson(get("/api/messages"), JsonArray.class);
        assertEquals(0, list.size(), list.toString());
    }

    @Test
    void aRealBundleIsListedAndItsContentShowsPackagedTextAndOverrideSideBySide() throws Exception {
        writeJar(
                configs.resolve("smp/smp-0.9.1.jar"),
                java.util.Map.of(
                        "messages/smp/en.properties", "welcome=Welcome\n",
                        "messages/smp/de.properties", "welcome=Willkommen\n"));
        final Path overrides = Files.createDirectories(configs.resolve("smp/smp/messages"));
        Files.writeString(overrides.resolve("de.properties"), "welcome=Servus\n", StandardCharsets.UTF_8);

        final JsonArray list = GSON.fromJson(get("/api/messages"), JsonArray.class);
        assertEquals(1, list.size(), list.toString());
        final String path = list.get(0).getAsJsonObject().get("path").getAsString();
        assertEquals("smp/smp", path);

        final JsonObject bundle = GSON.fromJson(get("/api/messages/" + path), JsonObject.class);
        final JsonObject welcome = entry(bundle, "welcome");
        assertEquals("Welcome", welcome.get("english").getAsString());
        assertEquals("Willkommen", welcome.get("german").getAsString());
        assertEquals("Servus", welcome.get("overrideGerman").getAsString());
        assertFalse(welcome.has("overrideEnglish"), "no override means the field is absent: " + welcome);
    }

    @Test
    void savingALineCreatesTheOverrideAndWarnsButStillSavesWhenAPlaceholderIsDropped() throws Exception {
        writeJar(
                configs.resolve("smp/smp-0.9.1.jar"),
                java.util.Map.of("messages/smp/en.properties", "greeting=Hello <_sender>\n"));
        Files.createDirectories(configs.resolve("smp/smp/messages"));

        final JsonObject saved = GSON.fromJson(
                put("/api/messages/smp/smp", "{\"changes\":{\"greeting\":{\"en\":\"Hello there\"}}}"),
                JsonObject.class);

        assertTrue(saved.getAsJsonArray("warnings").get(0).getAsString().contains("<_sender>"), saved.toString());
        assertEquals(
                "Hello there",
                entry(saved, "greeting").get("overrideEnglish").getAsString(),
                "a warning must not stop the save");
    }

    @Test
    void aPlaceholderTheSchemaDoesNotDeclareIsRefusedWithTheKeyAndNothingIsSaved() throws Exception {
        writeJar(
                configs.resolve("smp/smp-0.9.1.jar"),
                java.util.Map.of(
                        "messages/smp/en.properties", "greeting=Hello {player}\n",
                        "messages/smp/schema.json", """
                        {"bundle": "smp", "messages": [{"key": "greeting", "name": "Greeting",
                          "args": [{"name": "player", "component": false}], "section": ["Join"]}]}
                        """));
        Files.createDirectories(configs.resolve("smp/smp/messages"));

        final HttpResponse<String> refused =
                send("PUT", "/api/messages/smp/smp", "{\"changes\":{\"greeting\":{\"en\":\"Hello {name}\"}}}");

        assertEquals(400, refused.statusCode(), refused.body());
        assertTrue(refused.body().contains("greeting") && refused.body().contains("{name}"), refused.body());
        assertFalse(
                Files.exists(configs.resolve("smp/smp/messages/en.properties")),
                "a refused save must not write anything");
        final JsonObject greeting = entry(GSON.fromJson(get("/api/messages/smp/smp"), JsonObject.class), "greeting");
        assertEquals("Greeting", greeting.get("name").getAsString());
        assertEquals(
                "player",
                greeting.getAsJsonArray("args")
                        .get(0)
                        .getAsJsonObject()
                        .get("name")
                        .getAsString());
        assertEquals("Join", greeting.getAsJsonArray("section").get(0).getAsString());
    }

    @Test
    void resettingAKeyRemovesItFromTheOverrideRatherThanCopyingEnglishIntoIt() throws Exception {
        writeJar(
                configs.resolve("smp/smp-0.9.1.jar"),
                java.util.Map.of("messages/smp/en.properties", "welcome=Welcome\n"));
        Files.createDirectories(configs.resolve("smp/smp/messages"));
        put("/api/messages/smp/smp", "{\"changes\":{\"welcome\":{\"en\":\"Howdy\"}}}");

        final JsonObject afterReset = GSON.fromJson(
                put("/api/messages/smp/smp", "{\"changes\":{\"welcome\":{\"en\":null}}}"), JsonObject.class);

        assertFalse(entry(afterReset, "welcome").has("overrideEnglish"), afterReset.toString());
        assertTrue(afterReset.getAsJsonArray("warnings").isEmpty());
    }

    @Test
    void aBundleThatDoesNotExistIsA404() throws Exception {
        assertEquals(404, raw("/api/messages/no-such-thing").statusCode());
    }

    /**
     * The route re-reads the bot's message bundle on demand and reports unknown keys by name.
     *
     * Without it, a saved bot message would only take effect at the next restart of the container, with nothing on
     * the page saying so.
     */
    @Test
    void theBotsBundleIsReReadOnDemandAndUnknownKeysComeBackNamed() throws Exception {
        botBundle();
        bot = request -> Outcome.done(java.util.Map.of("unknown", ""));

        final JsonObject quiet = saveBot();
        assertEquals("APPLIED", quiet.get("status").getAsString(), quiet.toString());
        assertTrue(quiet.getAsJsonArray("unknown").isEmpty(), quiet.toString());
        assertEquals(new BotRequest.ReloadMessages("discord-bot"), asked.get(0).payload());
        assertEquals(Actor.STEWARD, asked.get(0).actor());

        bot = request -> Outcome.done(java.util.Map.of("unknown", "dm.grantd,dm.revokd"));
        final JsonObject typos = saveBot();
        assertEquals("APPLIED", typos.get("status").getAsString(), typos.toString());
        assertTrue(typos.get("message").getAsString().contains("dm.grantd"), typos.toString());
        assertEquals(
                List.of("dm.grantd", "dm.revokd"),
                typos.getAsJsonArray("unknown").asList().stream()
                        .map(element -> element.getAsString())
                        .toList());
    }

    @Test
    void aBotThatDidNotReReadIsSaidSoNotReportedAsApplied() throws Exception {
        botBundle();
        bot = request -> Outcome.failed(java.util.Map.of("error", "de.properties is not readable"));

        final JsonObject answer = saveBot();
        assertEquals("NO_ANSWER", answer.get("status").getAsString(), answer.toString());
    }

    /**
     * The answer this ticket exists for.
     *
     * Until now it was not a wrong answer, it was no answer: the page said "saved" and let the reader assume the line
     * was in force, and the bot went on sending the old one until somebody restarted the container for an unrelated
     * reason.
     */
    @Test
    void aBotThatIsNotRunningMeansSavedInForceAfterARestart() throws Exception {
        botBundle();
        // The default: the row is written and nobody ever claims it.
        final JsonObject answer = saveBot();
        assertEquals("NO_ANSWER", answer.get("status").getAsString(), answer.toString());
        assertTrue(
                inbox.recent(BotRequest.ReloadMessages.class, 1).stream()
                        .anyMatch(row -> row.status() == eu.nordtal.s2.database.inbox.InboxStatus.EXPIRED),
                "the row is still written - a bot that comes back"
                        + " inside its patience carries it out, which is the point of a row over a call");
    }

    @Test
    void bothLanguagesAreSavedInOneCall() throws Exception {
        smpBundle();

        final JsonObject saved = GSON.fromJson(
                put("/api/messages/smp/smp", "{\"changes\":{\"welcome\":{\"en\":\"Howdy\",\"de\":\"Servus\"}}}"),
                JsonObject.class);

        assertEquals("Howdy", entry(saved, "welcome").get("overrideEnglish").getAsString(), saved.toString());
        assertEquals("Servus", entry(saved, "welcome").get("overrideGerman").getAsString(), saved.toString());
    }

    @Test
    void savingAMinecraftBundleSendsThatPluginsReloadToItsConsole() throws Exception {
        smpBundle();

        final JsonObject saved = GSON.fromJson(
                put("/api/messages/smp/smp", "{\"changes\":{\"welcome\":{\"en\":\"Howdy\"}}}"), JsonObject.class);

        final JsonObject reload = saved.getAsJsonObject("reload");
        assertEquals("APPLIED", reload.get("status").getAsString(), saved.toString());
        assertEquals(List.of("smp: smp reload"), console);
        assertTrue(asked.isEmpty(), "no row belongs on the inbox for a service with a console");
    }

    @Test
    void aMinecraftServiceThatIsDownMeansSavedInForceOnceItRunsAgain() throws Exception {
        smpBundle();
        consoleDown = true;

        final JsonObject saved = GSON.fromJson(
                put("/api/messages/smp/smp", "{\"changes\":{\"welcome\":{\"en\":\"Howdy\"}}}"), JsonObject.class);

        assertEquals("NO_ANSWER", saved.getAsJsonObject("reload").get("status").getAsString(), saved.toString());
        assertEquals(
                "Howdy",
                entry(saved, "welcome").get("overrideEnglish").getAsString(),
                "a service that did not answer must not undo the save");
    }

    private void smpBundle() throws IOException {
        writeJar(
                configs.resolve("smp/smp-0.9.1.jar"),
                java.util.Map.of("messages/smp/en.properties", "welcome=Welcome\n"));
        Files.createDirectories(configs.resolve("smp/smp/messages"));
    }

    /** Saves one line of the bot's bundle and answers what came back under {@code reload}. */
    private JsonObject saveBot() throws Exception {
        return GSON.fromJson(
                        put("/api/messages/discord-bot", "{\"changes\":{\"dm.granted\":{\"en\":\"You are in now\"}}}"),
                        JsonObject.class)
                .getAsJsonObject("reload");
    }

    /** A bundle for the one service this route can actually reach. */
    private void botBundle() throws IOException {
        writeJar(
                configs.resolve("discord-bot/discord-bot-0.9.3.jar"),
                java.util.Map.of("messages/access/en.properties", "dm.granted=You are in\n"));
        Files.createDirectories(configs.resolve("discord-bot/messages"));
    }

    private static JsonObject entry(final JsonObject bundle, final String key) {
        for (final var element : bundle.getAsJsonArray("entries")) {
            final JsonObject row = element.getAsJsonObject();
            if (key.equals(row.get("key").getAsString())) {
                return row;
            }
        }
        throw new AssertionError("no key " + key + " in " + bundle);
    }

    private String get(final String path) throws Exception {
        final HttpResponse<String> response = raw(path);
        assertEquals(200, response.statusCode(), path + " answered " + response.body());
        return response.body();
    }

    private String put(final String path, final String body) throws Exception {
        final HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), path + " answered " + response.body());
        return response.body();
    }

    private HttpResponse<String> send(final String method, final String path, final String body) throws Exception {
        return http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> raw(final String path) throws Exception {
        final HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        request.GET();
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Writes a jar with the given entry name -> UTF-8 text content. */
    private static void writeJar(final Path jar, final java.util.Map<String, String> entries) throws IOException {
        Files.createDirectories(jar.getParent());
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (final var entry : entries.entrySet()) {
                out.putNextEntry(new JarEntry(entry.getKey()));
                out.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
    }
}
