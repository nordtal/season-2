package eu.nordtal.s2.steward.worker.api;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.javalin.Javalin;
import io.javalin.json.JavalinGson;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code PUT /api/config-raw/<file>} served over real HTTP - the raw editor's own save (steward/60).
 *
 * <p>Follows {@code MessagesApiIntegrationTest}'s shape: the contract steward-ui and the frontend
 * read is the HTTP one, not the methods behind it.</p>
 */
class ConfigApiRawIntegrationTest {

    private static final Gson GSON = new Gson();

    @TempDir
    Path configs;

    private Javalin app;
    private HttpClient http;
    private int port;

    @BeforeEach
    void start() {
        final ConfigApi api = new ConfigApi(configs, (service, command) -> { });
        app = Javalin.create(config -> {
            config.jsonMapper(new JavalinGson(new Gson(), true));
            config.routes.get("/api/config", api::list);
            config.routes.get("/api/config/<file>", api::one);
            config.routes.put("/api/config/<file>", api::save);
            config.routes.put("/api/config-raw/<file>", api::saveRaw);
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
    @DisplayName("what is typed lands on disk verbatim, and the answer says so")
    void savesVerbatimAndAnswersWithTheNewRevision() throws Exception {
        Files.writeString(configs.resolve("steward.txt"), "one\ntwo\n", StandardCharsets.UTF_8);
        final String revision = GSON.fromJson(get("/api/config/steward.txt"), JsonObject.class)
                .get("revision").getAsString();

        final JsonObject answer = GSON.fromJson(
                put("/api/config-raw/steward.txt", body(revision, "one\nTHREE\n")), JsonObject.class);

        assertEquals("one\nTHREE\n", Files.readString(configs.resolve("steward.txt"),
                StandardCharsets.UTF_8));
        assertEquals(true, answer.get("raw").getAsBoolean());
        assertEquals("one\nTHREE\n", answer.get("content").getAsString());
        assertTrue(answer.getAsJsonArray("warnings").isEmpty(), answer.toString());
        assertTrue(answer.has("revision") && !answer.get("revision").getAsString().isBlank(),
                answer.toString());
    }

    @Test
    @DisplayName("a syntax error is a warning naming the line, and the save still happens - nothing is refused")
    void syntaxErrorWarnsButStillSaves() throws Exception {
        Files.writeString(configs.resolve("broken.yml"), "one: 1\n", StandardCharsets.UTF_8);
        // "one: 1" alone parses as an ordinary one-key config file, which is the OTHER route -
        // GET /api/config/<file> answers `raw: false` with entries, and that answer's own
        // `revision` is what a save against it has to carry. Reading it through the same route the
        // real editor would is what keeps this test honest about which shape it is checking.
        final String revision = GSON.fromJson(get("/api/config/broken.yml"), JsonObject.class)
                .get("revision").getAsString();

        final String broken = "one: 1\ntwo: [unterminated\nthree: 3\n";
        final JsonObject answer = GSON.fromJson(
                put("/api/config-raw/broken.yml", body(revision, broken)), JsonObject.class);

        assertEquals(broken, Files.readString(configs.resolve("broken.yml"), StandardCharsets.UTF_8),
                "the save must have happened despite the warning");
        assertEquals(1, answer.getAsJsonArray("warnings").size(), answer.toString());
        assertTrue(answer.getAsJsonArray("warnings").get(0).getAsString().startsWith("Line 3:"),
                answer.toString());
    }

    @Test
    @DisplayName("a stale revision is refused with 409, and nothing is written")
    void staleRevisionIs409() throws Exception {
        final Path file = configs.resolve("stale.properties");
        Files.writeString(file, "one=1\n", StandardCharsets.UTF_8);
        final JsonObject read = GSON.fromJson(get("/api/config/stale.properties"), JsonObject.class);
        final String staleRevision = read.get("revision").getAsString();

        // Somebody else writes it first.
        Files.writeString(file, "one=somebody-else\n", StandardCharsets.UTF_8);

        final HttpResponse<String> response = raw("/api/config-raw/stale.properties",
                body(staleRevision, "one=this-should-not-land\n"));
        assertEquals(409, response.statusCode(), response.body());
        assertEquals("one=somebody-else\n", Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("a file that is not there at all is a 404, exactly like the parsed route")
    void unknownFileIs404() throws Exception {
        assertEquals(404, raw("/api/config-raw/no-such-file.yml",
                body(null, "anything")).statusCode());
    }

    private static String body(final String revision, final String content) {
        final JsonObject body = new JsonObject();
        body.addProperty("revision", revision);
        body.addProperty("content", content);
        return GSON.toJson(body);
    }

    private String get(final String path) throws Exception {
        final HttpResponse<String> response = http.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + path)).GET().build(),
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
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(requestBody))
                .build(), HttpResponse.BodyHandlers.ofString());
    }
}
