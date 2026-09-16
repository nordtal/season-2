package eu.nordtal.s2.steward.worker.api;

import com.google.gson.Gson;
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

    @BeforeEach
    void start() {
        final MessagesApi messages = new MessagesApi(configs, volumes);
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
                "{\"language\":\"en\",\"changes\":{\"greeting\":\"Hello there\"}}"), JsonObject.class);

        assertTrue(saved.getAsJsonArray("warnings").get(0).getAsString().contains("<_sender>"),
                saved.toString());
        assertEquals("Hello there", entry(saved, "greeting").get("overrideEnglish").getAsString(),
                "a warning must not stop the save - steward/60's rule applies here too");
    }

    @Test
    @DisplayName("resetting a key removes it from the override rather than copying English into it")
    void resettingRemovesTheOverride() throws Exception {
        writeJar(configs.resolve("smp/smp-0.9.1.jar"), java.util.Map.of(
                "messages/smp/en.properties", "welcome=Welcome\n"));
        Files.createDirectories(configs.resolve("smp/smp/messages"));
        put("/api/messages/smp/smp", "{\"language\":\"en\",\"changes\":{\"welcome\":\"Howdy\"}}");

        final JsonObject afterReset = GSON.fromJson(put("/api/messages/smp/smp",
                "{\"language\":\"en\",\"changes\":{\"welcome\":null}}"), JsonObject.class);

        assertFalse(entry(afterReset, "welcome").has("overrideEnglish"), afterReset.toString());
        assertTrue(afterReset.getAsJsonArray("warnings").isEmpty());
    }

    @Test
    @DisplayName("a bundle that does not exist is a 404")
    void anUnknownBundleIsNotFound() throws Exception {
        assertEquals(404, raw("/api/messages/no-such-thing", null).statusCode());
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
