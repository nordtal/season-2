package eu.nordtal.s2.steward.worker.api;

import com.google.gson.Gson;
import eu.nordtal.s2.common.access.AccessRequest;
import eu.nordtal.s2.common.access.AccessRequestKind;
import eu.nordtal.s2.common.access.AccessRequestSource;
import eu.nordtal.s2.common.access.AccessRequestStatus;
import eu.nordtal.s2.common.access.AccessRequests;
import eu.nordtal.s2.steward.worker.docker.DockerException;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.javalin.Javalin;
import io.javalin.json.JavalinGson;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MessagesApi} served over real HTTP - the contract steward-ui reads, not the methods behind
 * it (steward/48).
 *
 * <p><b>The red this class exists to have shown</b>: before {@link MessagesApi} and its two routes
 * existed, nothing under {@code /api/} could answer a question about a message bundle at all - the
 * ticket's own words are that {@code GET /api/config} "lists no bundle at all", and that remains true
 * on purpose (see {@link MessagesApi}'s javadoc for why the two stay apart): a bundle is answered by
 * its own route, {@code /api/messages}, which is the one this class asserts against instead.</p>
 */
class MessagesApiIntegrationTest {

    private static final Gson GSON = new Gson();

    @TempDir
    Path configs;

    @TempDir
    Path volumes;

    private Javalin app;
    private HttpClient http;
    private int port;
    private final Inbox inbox = new Inbox();
    /** Every console line sent, as {@code service: command}; {@link #consoleDown} makes it throw. */
    private final List<String> console = new ArrayList<>();
    private boolean consoleDown;

    @BeforeEach
    void start() {
        final MessagesApi messages = new MessagesApi(configs, volumes, inbox, (service, command) -> {
            if (consoleDown) {
                throw new DockerException("no running container for " + service);
            }
            console.add(service + ": " + command);
        });
        app = Javalin.create(config -> {
            config.jsonMapper(new JavalinGson(new Gson(), true));
            config.routes.get("/api/messages", messages::list);
            config.routes.get("/api/messages/<bundle>", messages::one);
            config.routes.put("/api/messages/<bundle>", messages::save);
        }).start(0);
        port = app.port();
        http = HttpClient.newHttpClient();
    }

    @AfterEach
    void stop() {
        if (app != null) {
            app.stop();
        }
    }

    @Test
    @DisplayName("an empty mount lists no bundle at all - the state before this route existed")
    void anEmptyMountListsNoBundle() throws Exception {
        final JsonArray list = GSON.fromJson(get("/api/messages"), JsonArray.class);
        assertEquals(0, list.size(), list.toString());
    }

    @Test
    @DisplayName("a real bundle is listed, and its content shows packaged text and override side by side")
    void aRealBundleIsListedAndRead() throws Exception {
        writeJar(configs.resolve("smp/smp-0.9.1.jar"), java.util.Map.of(
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
    @DisplayName("saving a line creates the override and warns, but still saves, when a placeholder is dropped")
    void savingWarnsOnADroppedPlaceholder() throws Exception {
        writeJar(configs.resolve("smp/smp-0.9.1.jar"), java.util.Map.of(
                "messages/smp/en.properties", "greeting=Hello <_sender>\n"));
        Files.createDirectories(configs.resolve("smp/smp/messages"));

        final JsonObject saved = GSON.fromJson(put("/api/messages/smp/smp",
                "{\"changes\":{\"greeting\":{\"en\":\"Hello there\"}}}"), JsonObject.class);

        assertTrue(saved.getAsJsonArray("warnings").get(0).getAsString().contains("<_sender>"),
                saved.toString());
        assertEquals("Hello there", entry(saved, "greeting").get("overrideEnglish").getAsString(),
                "a warning must not stop the save - steward/60's rule applies here too");
    }

    @Test
    @DisplayName("a placeholder the schema does not declare is refused with the key, and nothing is saved")
    void anUnknownPlaceholderIsRefused() throws Exception {
        writeJar(configs.resolve("smp/smp-0.9.1.jar"), java.util.Map.of(
                "messages/smp/en.properties", "greeting=Hello {player}\n",
                "messages/smp/schema.json", """
                        {"bundle": "smp", "messages": [{"key": "greeting", "name": "Greeting",
                          "args": [{"name": "player", "component": false}], "section": ["Join"]}]}
                        """));
        Files.createDirectories(configs.resolve("smp/smp/messages"));

        final HttpResponse<String> refused = send("PUT", "/api/messages/smp/smp",
                "{\"changes\":{\"greeting\":{\"en\":\"Hello {name}\"}}}");

        assertEquals(400, refused.statusCode(), refused.body());
        assertTrue(refused.body().contains("greeting") && refused.body().contains("{name}"), refused.body());
        assertFalse(Files.exists(configs.resolve("smp/smp/messages/en.properties")),
                "a refused save must not write anything");
        final JsonObject greeting = entry(GSON.fromJson(get("/api/messages/smp/smp"), JsonObject.class), "greeting");
        assertEquals("Greeting", greeting.get("name").getAsString());
        assertEquals("player", greeting.getAsJsonArray("args").get(0).getAsJsonObject().get("name").getAsString());
        assertEquals("Join", greeting.getAsJsonArray("section").get(0).getAsString());
    }

    @Test
    @DisplayName("resetting a key removes it from the override rather than copying English into it")
    void resettingRemovesTheOverride() throws Exception {
        writeJar(configs.resolve("smp/smp-0.9.1.jar"), java.util.Map.of(
                "messages/smp/en.properties", "welcome=Welcome\n"));
        Files.createDirectories(configs.resolve("smp/smp/messages"));
        put("/api/messages/smp/smp", "{\"changes\":{\"welcome\":{\"en\":\"Howdy\"}}}");

        final JsonObject afterReset = GSON.fromJson(put("/api/messages/smp/smp",
                "{\"changes\":{\"welcome\":{\"en\":null}}}"), JsonObject.class);

        assertFalse(entry(afterReset, "welcome").has("overrideEnglish"), afterReset.toString());
        assertTrue(afterReset.getAsJsonArray("warnings").isEmpty());
    }

    @Test
    @DisplayName("a bundle that does not exist is a 404")
    void anUnknownBundleIsNotFound() throws Exception {
        assertEquals(404, raw("/api/messages/no-such-thing", null).statusCode());
    }

    // ---------------------------------------------------------------------------------- reload

    /**
     * The three answers season-2-community/09 asks this route for, in the order the ticket names
     * them. Before it existed there was no fourth route at all and a saved bot message took effect
     * at the next restart of the container, with nothing on the page saying so - which is the whole
     * defect, and it is invisible rather than red.
     */
    @Test
    @DisplayName("the bot's bundle is re-read on demand, and unknown keys come back named")
    void reloadingTheBotsBundleReportsWhatItFound() throws Exception {
        botBundle();
        inbox.answer = request -> settled(request, AccessRequestStatus.DONE, "{\"unknown\":\"\"}");

        final JsonObject quiet = saveBot();
        assertEquals("APPLIED", quiet.get("status").getAsString(), quiet.toString());
        assertTrue(quiet.getAsJsonArray("unknown").isEmpty(), quiet.toString());
        assertEquals(AccessRequestKind.RELOAD_MESSAGES, inbox.asked.get(0).kind());
        assertEquals(AccessRequestSource.STEWARD, inbox.asked.get(0).source());

        inbox.answer = request -> settled(request, AccessRequestStatus.DONE,
                "{\"unknown\":\"dm.grantd,dm.revokd\"}");
        final JsonObject typos = saveBot();
        assertEquals("APPLIED", typos.get("status").getAsString(), typos.toString());
        assertTrue(typos.get("message").getAsString().contains("dm.grantd"), typos.toString());
        assertEquals(List.of("dm.grantd", "dm.revokd"),
                typos.getAsJsonArray("unknown").asList().stream()
                        .map(element -> element.getAsString()).toList());
    }

    @Test
    @DisplayName("a bot that did not re-read is said so, not reported as applied")
    void aFailedReloadIsNotAnAppliedOne() throws Exception {
        botBundle();
        inbox.answer = request -> settled(request, AccessRequestStatus.FAILED,
                "{\"error\":\"de.properties is not readable\"}");

        final JsonObject answer = saveBot();
        assertEquals("NO_ANSWER", answer.get("status").getAsString(), answer.toString());
    }

    /**
     * <b>The answer this ticket exists for.</b> Until now it was not a wrong answer, it was no
     * answer: the page said "saved" and let the reader assume the line was in force, and the bot
     * went on sending the old one until somebody restarted the container for an unrelated reason.
     */
    @Test
    @DisplayName("a bot that is not running means saved, in force after a restart")
    void aBotThatNeverAnswersIsSaidToNeedARestart() throws Exception {
        botBundle();
        // The default: the row is written and nobody ever claims it.
        final JsonObject answer = saveBot();
        assertEquals("NO_ANSWER", answer.get("status").getAsString(), answer.toString());
        assertEquals(1, inbox.asked.size(), "the row is still written - a bot that comes back"
                + " inside its patience carries it out, which is the point of a row over a call");
    }

    @Test
    @DisplayName("both languages are saved in one call")
    void bothLanguagesInOneCall() throws Exception {
        smpBundle();

        final JsonObject saved = GSON.fromJson(put("/api/messages/smp/smp",
                "{\"changes\":{\"welcome\":{\"en\":\"Howdy\",\"de\":\"Servus\"}}}"), JsonObject.class);

        assertEquals("Howdy", entry(saved, "welcome").get("overrideEnglish").getAsString(), saved.toString());
        assertEquals("Servus", entry(saved, "welcome").get("overrideGerman").getAsString(), saved.toString());
    }

    @Test
    @DisplayName("saving a Minecraft bundle sends that plugin's reload to its console")
    void savingAMinecraftBundleReloadsIt() throws Exception {
        smpBundle();

        final JsonObject saved = GSON.fromJson(put("/api/messages/smp/smp",
                "{\"changes\":{\"welcome\":{\"en\":\"Howdy\"}}}"), JsonObject.class);

        final JsonObject reload = saved.getAsJsonObject("reload");
        assertEquals("APPLIED", reload.get("status").getAsString(), saved.toString());
        assertEquals(List.of("smp: smp reload"), console);
        assertTrue(inbox.asked.isEmpty(), "no row belongs on the inbox for a service with a console");
    }

    @Test
    @DisplayName("a Minecraft service that is down means saved, in force once it runs again")
    void aStoppedMinecraftServiceIsNoAnswer() throws Exception {
        smpBundle();
        consoleDown = true;

        final JsonObject saved = GSON.fromJson(put("/api/messages/smp/smp",
                "{\"changes\":{\"welcome\":{\"en\":\"Howdy\"}}}"), JsonObject.class);

        assertEquals("NO_ANSWER", saved.getAsJsonObject("reload").get("status").getAsString(), saved.toString());
        assertEquals("Howdy", entry(saved, "welcome").get("overrideEnglish").getAsString(),
                "a service that did not answer must not undo the save");
    }

    private void smpBundle() throws IOException {
        writeJar(configs.resolve("smp/smp-0.9.1.jar"), java.util.Map.of(
                "messages/smp/en.properties", "welcome=Welcome\n"));
        Files.createDirectories(configs.resolve("smp/smp/messages"));
    }

    /** Saves one line of the bot's bundle and answers what came back under {@code reload}. */
    private JsonObject saveBot() throws Exception {
        return GSON.fromJson(put("/api/messages/discord-bot",
                "{\"changes\":{\"dm.granted\":{\"en\":\"You are in now\"}}}"), JsonObject.class)
                .getAsJsonObject("reload");
    }

    /** A bundle for the one service this route can actually reach. */
    private void botBundle() throws IOException {
        writeJar(configs.resolve("discord-bot/discord-bot-0.9.3.jar"), java.util.Map.of(
                "messages/access/en.properties", "dm.granted=You are in\n"));
        Files.createDirectories(configs.resolve("discord-bot/messages"));
    }

    private static AccessRequest settled(final AccessRequest request,
                                         final AccessRequestStatus status, final String result) {
        return new AccessRequest(request.id(), request.kind(), status, request.subject(),
                request.argument(), request.source(), request.requestedBy(), request.requested(),
                request.expires(), Instant.now(), Instant.now(), result);
    }

    /**
     * An inbox nobody polls, answering whatever the test decided the bot would have done.
     *
     * <p>A fake rather than Testcontainers because what is under test here is the route's three
     * answers, not the SQL - {@code AccessRequestsIntegrationTest} in {@code :common} is where the
     * table is held against a real PostgreSQL.</p>
     */
    private static final class Inbox implements AccessRequests {

        private final List<AccessRequest> asked = new ArrayList<>();
        private final List<AccessRequest> rows = new ArrayList<>();

        /** What the bot would have written back, or {@code null} for a bot that is not running. */
        private java.util.function.UnaryOperator<AccessRequest> answer = request -> null;

        @Override
        public AccessRequest submit(final NewAccessRequest request) {
            return submit(request, PATIENCE);
        }

        @Override
        public AccessRequest submit(final NewAccessRequest request, final Duration patience) {
            final AccessRequest row = new AccessRequest(asked.size() + 1L, request.kind(),
                    AccessRequestStatus.PENDING, request.subject(), request.argument(),
                    request.source(), request.requestedBy(), Instant.now(),
                    Instant.now().plus(patience), null, null, null);
            asked.add(row);
            final AccessRequest carriedOut = answer.apply(row);
            rows.add(carriedOut == null ? row : carriedOut);
            return row;
        }

        @Override
        public Optional<AccessRequest> outcome(final long id) {
            return rows.stream().filter(row -> row.id() == id).findFirst();
        }

        @Override
        public Optional<AccessRequest> claim() {
            return Optional.empty();
        }

        @Override
        public void finish(final long id, final boolean ok, final String result) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<AccessRequest> pending() {
            return List.of();
        }

        @Override
        public int expireDue() {
            return 0;
        }

        @Override
        public int purge(final Duration age) {
            return 0;
        }
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
        final HttpResponse<String> response = raw(path, null);
        assertEquals(200, response.statusCode(), path + " answered " + response.body());
        return response.body();
    }

    private String put(final String path, final String body) throws Exception {
        final HttpResponse<String> response = http.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), path + " answered " + response.body());
        return response.body();
    }

    private HttpResponse<String> send(final String method, final String path, final String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> raw(final String path, final String body) throws Exception {
        final HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + path));
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
