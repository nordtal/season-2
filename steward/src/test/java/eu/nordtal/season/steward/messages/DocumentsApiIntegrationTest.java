package eu.nordtal.season.steward.messages;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.common.json.Json;
import eu.nordtal.season.database.TestDatabase;
import eu.nordtal.season.database.document.DocumentStore;
import eu.nordtal.season.database.setting.SettingStore;
import eu.nordtal.season.internalapi.agent.AgentClient;
import eu.nordtal.season.messages.Messages;
import eu.nordtal.season.messages.document.Document;
import eu.nordtal.season.messages.document.DocumentMerge;
import eu.nordtal.season.messages.document.Documents;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests {@link DocumentsApi} over HTTP against a database. */
class DocumentsApiIntegrationTest {

    private static final Gson GSON = new Gson();

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 0, 0, 0, 13};

    private static final String DECLARATIONS = """
            [{"namespace":"smp","name":"welcome","area":"Join","shape":"MESSAGE",
              "values":{"player":{"kind":"text","example":"Alex"}},
              "slots":[{"id":"join","type":"BUTTON","required":true,"values":{}}]}]""";

    private static final String WELCOME = """
            {"parts":[
              {"type":"CONTAINER","accent":"#5865f2","parts":[
                {"type":"TEXT","lines":[{"text":{"en":"Welcome {player}","de":"welcome-de {player}"},"style":"TITLE"}]}]},
              {"type":"ROW","items":[{"slot":"join","label":{"en":"Join","de":"join-de"}}]}]}""";

    private Path scratch;
    private AgentStandIn agent;
    private Javalin app;
    private HttpClient http;
    private int port;
    private DocumentStore store;

    @BeforeEach
    void start() throws IOException {
        // Short, because a Unix socket path has a length limit.
        scratch = Files.createTempDirectory(Path.of("/tmp"), "documents");
        agent = new AgentStandIn(scratch, 0, config -> {}, new JsonObject());
        PluginJars.smp(
                agent.configs.resolve("smp/smp-0.9.1.jar"),
                Map.of(
                        "messages/smp/en.properties", "motd=Hello\n",
                        "documents/smp/declarations.json", DECLARATIONS,
                        "documents/smp/welcome.json", WELCOME));
        final javax.sql.DataSource database = TestDatabase.fresh().dataSource();
        store = DocumentStore.using(database);
        final DocumentsApi documents =
                new DocumentsApi(new AgentClient(agent.client()), store, SettingStore.using(database));
        app = Javalin.create(config -> {
                    config.jsonMapper(new JavalinGson(new Gson(), true));
                    ErrorHandlers.install(config, WebTexts.load().messages(), Messages.load("messages/database"));
                    config.routes.get("/api/documents", documents::list);
                    config.routes.get("/api/document-images/{id}", documents::image);
                    config.routes.post("/api/documents/render", documents::render);
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

    @Test
    void eachDocumentIsListedWithItsDeclarationAndThePackagedHash() throws Exception {
        final JsonObject view = view();

        assertEquals("welcome", view.getAsJsonObject("declaration").get("name").getAsString());
        assertEquals(
                Document.decode(WELCOME), Document.decode(view.get("packaged").toString()));
        assertEquals(packaged().hash(), view.get("hash").getAsString());
        assertFalse(view.has("written"));
    }

    /** A document written over an older release, which had a slot this one dropped. */
    @Test
    void aDocumentOverAnotherReleaseIsListedWithWhatWasAdapted() throws Exception {
        store.change(
                new DocumentStore.StoredDocument("smp", "welcome", Document.decode("""
                                {"parts":[{"type":"TEXT","lines":[{"text":{"en":"Hi {player}","de":"hi-de"}}]},
                                  {"type":"ROW","items":[{"slot":"join","label":{"en":"Join","de":"join-de"}},
                                    {"slot":"leave","label":{"en":"Leave","de":"leave-de"}}]}]}"""), "a".repeat(64)),
                Actor.STEWARD);

        final JsonObject view = view();
        assertEquals(
                List.of(new DocumentMerge.Adaptation(DocumentMerge.Change.SLOT_REMOVED, "leave")),
                List.of(Json.decode(view.get("adapted").toString(), DocumentMerge.Adaptation[].class)));
        assertEquals(
                1,
                Document.decode(view.get("written").toString())
                        .parts()
                        .get(1)
                        .allItems()
                        .size());
    }

    @Test
    void anUploadedImageIsServedWithItsType() throws Exception {
        final long id = store.upload("image/png", PNG, Actor.STEWARD);

        final HttpResponse<byte[]> image = http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/document-images/" + id))
                        .build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, image.statusCode());
        assertEquals("image/png", image.headers().firstValue("Content-Type").orElseThrow());
        assertArrayEquals(PNG, image.body());
        send("GET", "/api/document-images/999", new byte[0], 404);
    }

    @Test
    void aDraftIsDrawnByTheBotsRendererWithItsExamples() throws Exception {
        final JsonObject draft = new JsonObject();
        draft.add("declaration", GSON.toJsonTree(Documents.decode(DECLARATIONS).getFirst()));
        draft.add("document", Json.tree(WELCOME));
        draft.addProperty("language", "de");

        final JsonObject discord = GSON.fromJson(
                        send("POST", "/api/documents/render", draft.toString().getBytes(UTF_8), 200), JsonObject.class)
                .getAsJsonObject("discord");

        final JsonObject container = discord.getAsJsonArray("components").get(0).getAsJsonObject();
        assertEquals(0x5865f2, container.get("accent_color").getAsInt());
        assertEquals(
                "## welcome-de Alex",
                container
                        .getAsJsonArray("components")
                        .get(0)
                        .getAsJsonObject()
                        .get("content")
                        .getAsString());
        assertEquals(
                "join-de",
                discord.getAsJsonArray("components")
                        .get(1)
                        .getAsJsonObject()
                        .getAsJsonArray("components")
                        .get(0)
                        .getAsJsonObject()
                        .get("label")
                        .getAsString());
    }

    @Test
    void aDraftTheRendererCannotDrawIsRefused() throws Exception {
        final JsonObject draft = new JsonObject();
        draft.add("declaration", GSON.toJsonTree(Documents.decode(DECLARATIONS).getFirst()));
        draft.add("document", Json.tree("""
                {"parts":[{"type":"SECTION","parts":[{"type":"TEXT","lines":[{"text":{"en":"Hi"}}]}]}]}"""));
        draft.addProperty("language", "en");

        send("POST", "/api/documents/render", draft.toString().getBytes(UTF_8), 422);
        send("POST", "/api/documents/render", "{".getBytes(UTF_8), 400);
    }

    private static Documents.Packaged packaged() {
        return new Documents.Packaged(Documents.decode(DECLARATIONS).getFirst(), Document.decode(WELCOME));
    }

    private JsonObject view() throws Exception {
        return GSON.fromJson(send("GET", "/api/documents", new byte[0], 200), JsonObject.class)
                .getAsJsonArray("documents")
                .get(0)
                .getAsJsonObject();
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
