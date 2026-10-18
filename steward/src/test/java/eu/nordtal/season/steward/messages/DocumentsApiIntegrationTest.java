package eu.nordtal.season.steward.messages;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
                    config.http.maxRequestSize = DocumentsApi.IMAGE_BYTES;
                    ErrorHandlers.install(config, WebTexts.load().messages(), Messages.load("messages/database"));
                    config.routes.get("/api/documents", documents::list);
                    config.routes.put("/api/documents/{namespace}/{name}", ctx -> documents.save(ctx, Actor.STEWARD));
                    config.routes.post("/api/document-images", ctx -> documents.upload(ctx, Actor.STEWARD));
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
        assertFalse(view.get("outdated").getAsBoolean());
        assertEquals(
                "text",
                view.getAsJsonObject("named")
                        .getAsJsonObject("join.label")
                        .get("kind")
                        .getAsString());
    }

    @Test
    void aSavedDocumentIsStoredOverThePackagedHashAndListedAsWritten() throws Exception {
        final String written = WELCOME.replace("Welcome {player}", "Hello {player}");

        final JsonObject list = GSON.fromJson(
                send("PUT", "/api/documents/smp/welcome", written.getBytes(UTF_8), 200), JsonObject.class);

        final DocumentStore.StoredDocument stored =
                store.documents(List.of("smp")).getFirst();
        assertEquals(Document.decode(written), stored.document());
        assertEquals(packaged().hash(), stored.replaced());
        final JsonObject view = list.getAsJsonArray("documents").get(0).getAsJsonObject();
        assertEquals(
                Document.decode(written), Document.decode(view.get("written").toString()));
        assertFalse(view.get("outdated").getAsBoolean());
    }

    @Test
    void aSaveTheCheckRefusesWritesNothing() throws Exception {
        final String withoutJoin = """
                {"parts":[{"type":"TEXT","lines":[{"text":{"en":"Hi {player}"}}]}]}""";

        final String refused = send("PUT", "/api/documents/smp/welcome", withoutJoin.getBytes(UTF_8), 400);

        assertTrue(refused.contains("it does not place the button join"), refused);
        assertEquals(List.of(), store.documents(List.of("smp")));
        send("PUT", "/api/documents/smp/nothing", WELCOME.getBytes(UTF_8), 404);
        send("PUT", "/api/documents/smp/welcome", "{".getBytes(UTF_8), 400);
        send(
                "PUT",
                "/api/documents/smp/welcome",
                WELCOME.replace("Welcome", "{gone}").getBytes(UTF_8),
                400);
        send(
                "PUT",
                "/api/documents/smp/welcome",
                WELCOME.replace("#5865f2", "blue").getBytes(UTF_8),
                400);
        send("PUT", "/api/documents/smp/welcome", """
                {"parts":[{"type":"GALLERY","media":["upload://99"]},
                  {"type":"ROW","items":[{"slot":"join","label":{"en":"Join"}}]}]}""".getBytes(UTF_8), 400);
        assertEquals(List.of(), store.documents(List.of("smp")));
    }

    @Test
    void aDocumentOverAnOlderPackagedOneIsMarkedOutdated() throws Exception {
        store.change(
                new DocumentStore.StoredDocument("smp", "welcome", Document.decode(WELCOME), "b".repeat(64)),
                Actor.STEWARD);

        assertTrue(view().get("outdated").getAsBoolean());
    }

    @Test
    void anUploadIsStoredByTheTypeItsBytesShowAndShownByItsAddress() throws Exception {
        final JsonObject uploaded = GSON.fromJson(send("POST", "/api/document-images", PNG, 200), JsonObject.class);

        final long id = uploaded.get("id").getAsLong();
        assertEquals("upload://" + id, uploaded.get("address").getAsString());
        assertEquals("image/png", store.image(id).orElseThrow().mediaType());
        send("POST", "/api/document-images", "not an image".getBytes(UTF_8), 400);
        send("POST", "/api/document-images", new byte[DocumentsApi.IMAGE_BYTES + 1], 413);
        final String shown = WELCOME.replace(
                "{\"type\":\"ROW\"", "{\"type\":\"GALLERY\",\"media\":[\"upload://" + id + "\"]},{\"type\":\"ROW\"");
        send("PUT", "/api/documents/smp/welcome", shown.getBytes(UTF_8), 200);
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

        final JsonObject drawing = GSON.fromJson(
                send("POST", "/api/documents/render", draft.toString().getBytes(UTF_8), 200), JsonObject.class);
        final JsonObject discord = drawing.getAsJsonObject("discord");

        assertEquals(4, drawing.get("components").getAsInt());
        assertEquals(0, drawing.getAsJsonArray("problems").size());

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
    void aMenuIsDrawnByTheGamesRendererWithItsExamples() throws Exception {
        final JsonObject draft = new JsonObject();
        draft.add("declaration", Json.tree("""
                {"namespace":"smp","name":"wheel","area":"Menus","shape":"MENU",
                  "values":{"spins":{"kind":"text","example":"3"}},
                  "slots":[{"id":"hub","type":"ITEM","required":true,"values":{"target":{"kind":"text","example":"Spawn"}}},
                    {"id":"left","type":"LABEL","required":true,"values":{}}]}"""));
        draft.add("document", Json.tree("""
                {"parts":[
                  {"type":"TEXT","lines":[{"style":"TITLE","text":{"en":"Wheel","de":"wheel-de"}}]},
                  {"type":"ITEM","slot":"hub","lines":[
                    {"text":{"en":"To {target}","de":"to-de {target}"}},{"text":{"en":"{spins} spins","de":"{spins} spins-de"}}]},
                  {"type":"LABEL","slot":"left","lines":[{"text":{"en":"{spins} left","de":"{spins} left-de"}}]}]}"""));
        draft.addProperty("language", "de");

        final JsonObject drawing = GSON.fromJson(
                send("POST", "/api/documents/render", draft.toString().getBytes(UTF_8), 200), JsonObject.class);
        final JsonObject menu = drawing.getAsJsonObject("menu");

        assertEquals(0, drawing.getAsJsonArray("problems").size(), drawing.toString());
        assertEquals("wheel-de", menu.get("title").getAsString());
        assertEquals(
                "[{\"slot\":\"hub\",\"type\":\"ITEM\",\"lines\":[\"to-de Spawn\",\"3 spins-de\"]},"
                        + "{\"slot\":\"left\",\"type\":\"LABEL\",\"lines\":[\"3 left-de\"]}]",
                menu.getAsJsonArray("parts").toString());
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
