package eu.nordtal.s2.steward.worker.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.javalin.Javalin;
import io.javalin.json.JavalinGson;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code PUT /api/config-raw/<file>} served over real HTTP, the raw editor's own save. */
class ConfigApiRawIntegrationTest {

    private static final Gson GSON = new Gson();

    @TempDir
    Path configs;

    private Javalin app;
    private HttpClient http;
    private int port;

    @BeforeEach
    void start() {
        final ConfigApi api = new ConfigApi(configs, service -> java.util.Optional.empty());
        app = Javalin.create(config -> {
                    config.jsonMapper(new JavalinGson(new Gson(), true));
                    config.routes.get("/api/config", api::list);
                    config.routes.get("/api/config/<file>", api::one);
                    config.routes.put("/api/config/<file>", api::save);
                    config.routes.put("/api/config-raw/<file>", api::saveRaw);
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
    }

    @Test
    void whatIsTypedLandsOnDiskVerbatimAndTheAnswerSaysSo() throws Exception {
        Files.writeString(configs.resolve("steward.txt"), "one\ntwo\n", StandardCharsets.UTF_8);
        final String revision = GSON.fromJson(get("/api/config/steward.txt"), JsonObject.class)
                .get("revision")
                .getAsString();

        final JsonObject answer =
                GSON.fromJson(put("/api/config-raw/steward.txt", body(revision, "one\nTHREE\n")), JsonObject.class);

        assertEquals("one\nTHREE\n", Files.readString(configs.resolve("steward.txt"), StandardCharsets.UTF_8));
        assertEquals(true, answer.get("raw").getAsBoolean());
        assertEquals("one\nTHREE\n", answer.get("content").getAsString());
        assertTrue(answer.getAsJsonArray("warnings").isEmpty(), answer.toString());
        assertTrue(
                answer.has("revision") && !answer.get("revision").getAsString().isBlank(), answer.toString());
    }

    @Test
    void aSyntaxErrorIsAWarningNamingTheLineAndTheSaveStillHappensNothingIsRefused() throws Exception {
        Files.writeString(configs.resolve("broken.yml"), "one: 1\n", StandardCharsets.UTF_8);
        // "one: 1" parses fine, so GET /api/config/<file> answers `raw: false`; its `revision` is what a save carries.
        final String revision = GSON.fromJson(get("/api/config/broken.yml"), JsonObject.class)
                .get("revision")
                .getAsString();

        final String broken = "one: 1\ntwo: [unterminated\nthree: 3\n";
        final JsonObject answer =
                GSON.fromJson(put("/api/config-raw/broken.yml", body(revision, broken)), JsonObject.class);

        assertEquals(
                broken,
                Files.readString(configs.resolve("broken.yml"), StandardCharsets.UTF_8),
                "the save must have happened despite the warning");
        assertEquals(1, answer.getAsJsonArray("warnings").size(), answer.toString());
        assertTrue(answer.getAsJsonArray("warnings").get(0).getAsString().startsWith("Line 3:"), answer.toString());
    }

    @Test
    void aStaleRevisionIsRefusedWith409AndNothingIsWritten() throws Exception {
        final Path file = configs.resolve("stale.properties");
        Files.writeString(file, "one=1\n", StandardCharsets.UTF_8);
        final JsonObject read = GSON.fromJson(get("/api/config/stale.properties"), JsonObject.class);
        final String staleRevision = read.get("revision").getAsString();

        // Somebody else writes it first.
        Files.writeString(file, "one=somebody-else\n", StandardCharsets.UTF_8);

        final HttpResponse<String> response =
                raw("/api/config-raw/stale.properties", body(staleRevision, "one=this-should-not-land\n"));
        assertEquals(409, response.statusCode(), response.body());
        assertEquals("one=somebody-else\n", Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void aFileThatIsNotThereAtAllIsA404ExactlyLikeTheParsedRoute() throws Exception {
        assertEquals(
                404,
                raw("/api/config-raw/no-such-file.yml", body(null, "anything")).statusCode());
    }

    private static String body(final String revision, final String content) {
        final JsonObject body = new JsonObject();
        body.addProperty("revision", revision);
        body.addProperty("content", content);
        return GSON.toJson(body);
    }

    private String get(final String path) throws Exception {
        final HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), path + " answered " + response.body());
        return response.body();
    }

    private String put(final String path, final String requestBody) throws Exception {
        final HttpResponse<String> response = raw(path, requestBody);
        assertEquals(200, response.statusCode(), path + " answered " + response.body());
        return response.body();
    }

    private HttpResponse<String> raw(final String path, final String requestBody) throws Exception {
        return http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString(requestBody))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
