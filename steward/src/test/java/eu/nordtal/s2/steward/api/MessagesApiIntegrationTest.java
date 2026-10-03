package eu.nordtal.s2.steward.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import eu.nordtal.s2.common.id.Actor;
import eu.nordtal.s2.database.TestDatabase;
import eu.nordtal.s2.database.message.MessageOverrideStore;
import eu.nordtal.s2.internalapi.agent.AgentClient;
import eu.nordtal.s2.messages.MessageOverride;
import eu.nordtal.s2.messages.PackagedTexts;
import eu.nordtal.s2.steward.web.ErrorHandlers;
import eu.nordtal.s2.stewardagent.AgentStandIn;
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
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@link MessagesApi} served over real HTTP, the contract the frontend reads. */
class MessagesApiIntegrationTest {

    private static final Gson GSON = new Gson();

    /** The stand-in agent's, which reads the bundles; filled per test. */
    private Path configs;

    private Path scratch;
    private AgentStandIn agent;

    private Javalin app;
    private HttpClient http;
    private int port;
    private MessageOverrideStore store;

    @BeforeEach
    void start() throws IOException {
        // Short, since a Unix socket path has a length limit the default temp directory can exceed.
        scratch = Files.createTempDirectory(Path.of("/tmp"), "messages");
        agent = new AgentStandIn(scratch, 0, config -> {});
        configs = agent.configs;
        store = MessageOverrideStore.using(TestDatabase.fresh().dataSource());
        final MessagesApi messages = new MessagesApi(new AgentClient(agent.client()), store);
        app = Javalin.create(config -> {
                    config.jsonMapper(new JavalinGson(new Gson(), true));
                    ErrorHandlers.install(config);
                    config.routes.get("/api/messages", messages::list);
                    config.routes.get("/api/messages/<bundle>", messages::one);
                    config.routes.put("/api/messages/<bundle>", ctx -> messages.save(ctx, Actor.STEWARD));
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
        try {
            agent.close();
            try (var files = Files.walk(scratch)) {
                files.sorted(java.util.Comparator.reverseOrder())
                        .forEach(path -> path.toFile().delete());
            }
        } catch (final IOException e) {
            throw new java.io.UncheckedIOException(e);
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
        store.change("smp", "welcome", "de", List.of("Servus"), null, Actor.STEWARD);

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
                java.util.Map.of(
                        "messages/smp/en.properties", "greeting=Hello {player}\n",
                        "messages/smp/schema.json", """
                        {"bundle": "smp", "messages": [{"key": "greeting", "name": "Greeting",
                          "args": [{"name": "player", "kind": "text", "example": "Alex", "action": false}],
                          "section": [], "format": "MINIMESSAGE", "shown": "CHAT"}],
                         "contexts": {}, "globals": []}
                        """));

        final JsonObject saved = GSON.fromJson(
                put("/api/messages/smp/smp", "{\"changes\":{\"greeting\":{\"en\":\"Hello there\"}}}"),
                JsonObject.class);

        assertTrue(saved.getAsJsonArray("warnings").get(0).getAsString().contains("player"), saved.toString());
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
                          "args": [{"name": "player", "kind": "text", "example": "Alex", "action": false}],
                          "section": ["Join"], "format": "MINIMESSAGE", "shown": "CHAT"}],
                         "contexts": {}, "globals": []}
                        """));

        final HttpResponse<String> refused =
                send("PUT", "/api/messages/smp/smp", "{\"changes\":{\"greeting\":{\"en\":\"Hello {name}\"}}}");

        assertEquals(400, refused.statusCode(), refused.body());
        assertTrue(refused.body().contains("greeting") && refused.body().contains("{name}"), refused.body());
        assertEquals(List.of(), store.overrides(Set.of("smp")), "a refused save must not write anything");
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
    void aSaveIsARowUnderThePackagedBundleOfItsKeyAndAppliesAtOnce() throws Exception {
        writeJar(
                configs.resolve("smp/smp-0.9.1.jar"),
                java.util.Map.of(
                        "messages/smp/en.properties", "welcome=Welcome\n",
                        "messages/paper-common/en.properties", "reload.done=Reloaded\n"));

        final JsonObject saved = GSON.fromJson(
                put("/api/messages/smp/smp", "{\"changes\":{\"reload.done\":{\"en\":\"Done\"}}}"), JsonObject.class);

        assertEquals("APPLIED", saved.getAsJsonObject("reload").get("status").getAsString(), saved.toString());
        assertEquals(
                List.of(new MessageOverride(
                        "paper-common", "reload.done", "en", 0, "Done", PackagedTexts.hash(List.of("Reloaded")))),
                store.overrides(Set.of("paper-common")));
    }

    @Test
    void aKeyTheBundleDoesNotDeclareIsRefusedAndNothingIsSaved() throws Exception {
        smpBundle();

        final HttpResponse<String> refused =
                send("PUT", "/api/messages/smp/smp", "{\"changes\":{\"welcom\":{\"en\":\"Hi\"}}}");

        assertEquals(400, refused.statusCode(), refused.body());
        assertTrue(refused.body().contains("welcom"), refused.body());
        assertEquals(List.of(), store.overrides(Set.of("smp")));
    }

    private void smpBundle() throws IOException {
        writeJar(
                configs.resolve("smp/smp-0.9.1.jar"),
                java.util.Map.of("messages/smp/en.properties", "welcome=Welcome\n"));
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
