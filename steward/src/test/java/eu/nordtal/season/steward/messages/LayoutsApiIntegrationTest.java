package eu.nordtal.season.steward.messages;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.common.json.Json;
import eu.nordtal.season.database.TestDatabase;
import eu.nordtal.season.database.layout.LayoutStore;
import eu.nordtal.season.database.setting.SettingStore;
import eu.nordtal.season.internalapi.agent.AgentClient;
import eu.nordtal.season.messages.Messages;
import eu.nordtal.season.messages.Tone;
import eu.nordtal.season.messages.spec.layout.Element;
import eu.nordtal.season.messages.spec.layout.Layout;
import eu.nordtal.season.messages.spec.layout.LayoutSchema;
import eu.nordtal.season.messages.spec.layout.Line;
import eu.nordtal.season.messages.spec.layout.Part;
import eu.nordtal.season.messages.text.Declaration;
import eu.nordtal.season.messages.value.Kind;
import eu.nordtal.season.steward.ErrorHandlers;
import eu.nordtal.season.steward.texts.WebTexts;
import eu.nordtal.season.stewardagent.AgentStandIn;
import eu.nordtal.season.stewardagent.PluginJars;
import io.javalin.Javalin;
import io.javalin.json.JavalinGson;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@link LayoutsApi} served over real HTTP against a real database, the contract the frontend reads. */
class LayoutsApiIntegrationTest {

    private static final Gson GSON = new Gson();

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 0, 0, 0, 13};

    private static final Layout WELCOME = Layout.discord(
            "smp",
            "welcome",
            "Join",
            List.of(Element.button("join", "smp/join")),
            Part.container(Tone.BRAND, Part.text(Line.title("smp/welcome"))),
            Part.row("join"));

    private Path scratch;
    private AgentStandIn agent;
    private Javalin app;
    private HttpClient http;
    private int port;
    private LayoutStore store;

    @BeforeEach
    void start() throws IOException {
        // Short, since a Unix socket path has a length limit the default temp directory can exceed.
        scratch = Files.createTempDirectory(Path.of("/tmp"), "layouts");
        agent = new AgentStandIn(scratch, 0, config -> {}, new JsonObject());
        final LayoutSchema.Entry entry = new LayoutSchema.Entry(
                WELCOME,
                new Declaration(Map.of("player", Kind.TEXT), Set.of(), Set.of(), false, 0, Map.of(), false),
                List.of("smp/welcome", "smp/join"));
        PluginJars.smp(
                agent.configs.resolve("smp/smp-0.9.1.jar"),
                Map.of(
                        "messages/smp/en.properties",
                        "welcome=Welcome {player}\njoin=Join\n",
                        "messages/smp/layouts.json",
                        LayoutSchema.json(List.of(entry))));
        final javax.sql.DataSource database = TestDatabase.fresh().dataSource();
        store = LayoutStore.using(database);
        final LayoutsApi layouts = new LayoutsApi(new AgentClient(agent.client()), store, SettingStore.using(database));
        app = Javalin.create(config -> {
                    config.jsonMapper(new JavalinGson(new Gson(), true));
                    config.http.maxRequestSize = LayoutsApi.IMAGE_BYTES;
                    ErrorHandlers.install(config, WebTexts.load().messages(), Messages.load("messages/database"));
                    config.routes.get("/api/layouts", layouts::list);
                    config.routes.put("/api/layouts/{bundle}/{layout}", ctx -> layouts.save(ctx, Actor.STEWARD));
                    config.routes.delete("/api/layouts/{bundle}/{layout}", layouts::remove);
                    config.routes.post("/api/layout-images", ctx -> layouts.upload(ctx, Actor.STEWARD));
                    config.routes.get("/api/layout-images/{id}", layouts::image);
                })
                .start(0);
        port = app.port();
        http = HttpClient.newHttpClient();
    }

    @AfterEach
    void stop() throws IOException {
        if (app != null) {
            app.stop();
        }
        agent.close();
        try (var files = Files.walk(scratch)) {
            files.sorted(Comparator.reverseOrder())
                    .forEach(path -> path.toFile().delete());
        }
    }

    /** A rebuild that moves the button, adds a title, a link button and the uploaded image. */
    private static String rebuild(final String address, final String title) {
        return Json.encode(Map.of(
                "parts",
                List.of(
                        Part.row("join", "site"),
                        Part.container(
                                Tone.BRAND,
                                Part.text(Line.added(Line.Style.TITLE, Map.of("en", title))),
                                Part.text(Line.text("smp/welcome")),
                                Part.gallery(address))),
                "links",
                List.of(Element.link("site", "https://nordtal.eu", Map.of("en", "Site", "de", "Seite")))));
    }

    @Test
    void anUploadedImageIsStoredOnceAndServedWithItsType() throws Exception {
        final JsonObject first = GSON.fromJson(send("POST", "/api/layout-images", PNG, 200), JsonObject.class);
        final JsonObject again = GSON.fromJson(send("POST", "/api/layout-images", PNG, 200), JsonObject.class);
        assertEquals(first, again);
        assertEquals(
                "upload://" + first.get("id").getAsLong(), first.get("address").getAsString());

        final HttpResponse<byte[]> image = http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/layout-images/"
                                + first.get("id").getAsLong()))
                        .build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, image.statusCode());
        assertEquals("image/png", image.headers().firstValue("Content-Type").orElseThrow());
        assertArrayEquals(PNG, image.body());
        send("GET", "/api/layout-images/999", new byte[0], 404);
    }

    @Test
    void aFileThatIsNoImageIsRefusedAndOneAboveTheCapIsTooLarge() throws Exception {
        send("POST", "/api/layout-images", "<svg/>".getBytes(java.nio.charset.StandardCharsets.UTF_8), 400);
        final byte[] large = new byte[LayoutsApi.IMAGE_BYTES + 1];
        System.arraycopy(PNG, 0, large, 0, PNG.length);
        send("POST", "/api/layout-images", large, 413);
    }

    @Test
    void aSavedRebuildIsListedBesideItsDeclarationAndADeleteRemovesIt() throws Exception {
        final String address = GSON.fromJson(send("POST", "/api/layout-images", PNG, 200), JsonObject.class)
                .get("address")
                .getAsString();
        send("PUT", "/api/layouts/smp/welcome", bytes(rebuild(address, "Hi {player}")), 200);

        final JsonObject view = GSON.fromJson(get(), JsonObject.class)
                .getAsJsonArray("layouts")
                .get(0)
                .getAsJsonObject();
        assertEquals(LayoutSchema.hash(WELCOME), view.get("hash").getAsString());
        assertEquals(false, view.get("stale").getAsBoolean());
        final Layout rebuild = Json.decode(view.get("rebuild").toString(), Layout.class);
        assertEquals(List.of("join", "site"), rebuild.parts().getFirst().elements());
        assertEquals(List.of(Map.of("en", "Hi {player}"), Map.of("de", "Seite", "en", "Site")), rebuild.addedTexts());
        assertEquals(1, store.layouts(Set.of("smp")).size());

        send("DELETE", "/api/layouts/smp/welcome", new byte[0], 200);
        assertFalse(
                GSON.fromJson(get(), JsonObject.class)
                        .getAsJsonArray("layouts")
                        .get(0)
                        .getAsJsonObject()
                        .has("rebuild"),
                "a removed rebuild is answered as none");
    }

    @Test
    void aRebuildTheCheckOrTheValidatorRefusesWritesNothing() throws Exception {
        final String dropped = Json.encode(Map.of("parts", List.of(Part.text(Line.text("smp/welcome")))));
        assertTrue(send("PUT", "/api/layouts/smp/welcome", bytes(dropped), 400).contains("does not place"));
        send("PUT", "/api/layouts/smp/welcome", bytes(rebuild("upload://41", "Hi")), 400);
        send("PUT", "/api/layouts/smp/welcome", bytes(rebuild("https://nordtal.eu/a.png", "Hi {nobody}")), 400);
        send("PUT", "/api/layouts/smp/nothing", bytes(rebuild("https://nordtal.eu/a.png", "Hi")), 404);
        assertEquals(List.of(), store.layouts(Set.of("smp")));
    }

    private static byte[] bytes(final String text) {
        return text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private String get() throws Exception {
        return send("GET", "/api/layouts", new byte[0], 200);
    }

    private String send(final String method, final String path, final byte[] body, final int status) throws Exception {
        final HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .method(method, HttpRequest.BodyPublishers.ofByteArray(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(status, response.statusCode(), method + " " + path + " answered " + response.body());
        return response.body();
    }
}
