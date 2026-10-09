package eu.nordtal.season.steward.messages;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.database.TestDatabase;
import eu.nordtal.season.database.inbox.MessagePreview;
import eu.nordtal.season.database.message.MessageOverrideStore;
import eu.nordtal.season.database.setting.SettingStore;
import eu.nordtal.season.internalapi.agent.AgentClient;
import eu.nordtal.season.messages.MessageOverride;
import eu.nordtal.season.messages.Messages;
import eu.nordtal.season.messages.PackagedTexts;
import eu.nordtal.season.messages.Tone;
import eu.nordtal.season.messages.spec.Display;
import eu.nordtal.season.messages.value.DisplayName;
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
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
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
    private SettingStore settings;

    /** What the last {@code POST /api/message-preview} read, which the web would ask for. */
    private MessagePreview previewed;

    /** The bundles the last {@code PUT /api/messages} wrote, which the route journals. */
    private List<String> journalled;

    @BeforeEach
    void start() throws IOException {
        // Short, since a Unix socket path has a length limit the default temp directory can exceed.
        scratch = Files.createTempDirectory(Path.of("/tmp"), "messages");
        // Two more servers, so a bundle several jars carry is found on each.
        agent = new AgentStandIn(scratch, 0, config -> {}, GSON.fromJson("""
                {"limbo": {"image": "ghcr.io/nordtal/minecraft:latest"},
                 "proxy": {"image": "ghcr.io/nordtal/minecraft:latest"}}
                """, JsonObject.class));
        configs = agent.configs;
        final javax.sql.DataSource database = TestDatabase.fresh().dataSource();
        store = MessageOverrideStore.using(database);
        settings = SettingStore.using(database);
        final MessagesApi messages = new MessagesApi(new AgentClient(agent.client()), store, settings);
        app = Javalin.create(config -> {
                    config.jsonMapper(new JavalinGson(new Gson(), true));
                    ErrorHandlers.install(config, WebTexts.load().messages(), Messages.load("messages/database"));
                    config.routes.get("/api/messages", messages::list);
                    config.routes.get("/api/message-fallbacks", messages::fallbacks);
                    config.routes.get("/api/message-check", messages::check);
                    config.routes.get("/api/message-syntax", messages::syntax);
                    config.routes.put("/api/messages", ctx -> journalled = messages.save(ctx, Actor.STEWARD));
                    config.routes.post("/api/message-preview", ctx -> {
                        previewed = messages.preview(ctx);
                        ctx.status(204);
                    });
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
    void anEmptyMountListsNoTextAtAll() throws Exception {
        final JsonArray texts =
                GSON.fromJson(get("/api/messages"), JsonObject.class).getAsJsonArray("texts");
        assertEquals(0, texts.size(), texts.toString());
    }

    @Test
    void aRealBundlesTextsAreListedWithPackagedTextAndOverrideSideBySide() throws Exception {
        PluginJars.smp(
                configs.resolve("smp/smp-0.9.1.jar"),
                java.util.Map.of(
                        "messages/smp/en.properties", "welcome=Welcome\n",
                        "messages/smp/de.properties", "welcome=Willkommen\n"));
        store.change("smp", "welcome", "de", List.of("Servus"), List.of("Willkommen"), Actor.STEWARD);

        final JsonObject texts = GSON.fromJson(get("/api/messages"), JsonObject.class);
        assertEquals(1, texts.getAsJsonArray("texts").size(), texts.toString());
        assertEquals("smp/smp", text(texts, "smp", "welcome").get("path").getAsString());

        final JsonObject welcome = entry(texts, "smp", "welcome");
        assertEquals(List.of("Welcome"), texts(welcome, "texts", "en"));
        assertEquals(List.of("Willkommen"), texts(welcome, "texts", "de"));
        assertEquals(List.of("Servus"), texts(welcome, "overrides", "de"));
        assertFalse(welcome.getAsJsonObject("overrides").has("en"), "no override means no language: " + welcome);
    }

    @Test
    void theSyntaxIsEveryToneWithItsColourAndEveryKindWithItsStyles() throws Exception {
        final JsonObject syntax = GSON.fromJson(get("/api/message-syntax"), JsonObject.class);
        final JsonObject tones = syntax.getAsJsonObject("tones");
        assertEquals(Tone.values().length, tones.size(), tones.toString());
        assertEquals("neutral", tones.keySet().iterator().next(), "in the palette's order: " + tones);
        assertEquals("#8ba888", tones.get("good").getAsString());
        final JsonObject kinds = syntax.getAsJsonObject("kinds");
        assertEquals(Kind.values().length, kinds.size(), kinds.toString());
        assertEquals(
                "[\"clock\",\"long\",\"minutes\",\"short\"]",
                kinds.get("duration").toString());
        assertEquals("[]", kinds.get("text").toString());
    }

    @Test
    void everyLanguageAndEveryVariantIsReadAndSaved() throws Exception {
        PluginJars.smp(
                configs.resolve("smp/smp-0.9.1.jar"),
                java.util.Map.of(
                        "messages/smp/en.properties", "welcome=Welcome\nwelcome[1]=Hi\n",
                        "messages/smp/fr.properties", "welcome=Bienvenue\n"));

        final JsonObject saved = GSON.fromJson(
                put("/api/messages", "{\"changes\":{\"smp\":{\"welcome\":{\"fr\":[\"Salut\",\"Coucou\"]}}}}"),
                JsonObject.class);

        final JsonObject welcome = entry(saved.getAsJsonObject("texts"), "smp", "welcome");
        assertEquals(List.of("Welcome", "Hi"), texts(welcome, "texts", "en"), saved.toString());
        assertEquals(List.of("Bienvenue"), texts(welcome, "texts", "fr"), saved.toString());
        assertEquals(List.of("Salut", "Coucou"), texts(welcome, "overrides", "fr"), saved.toString());
        assertEquals(2, store.overrides(Set.of("smp")).size(), "one row per variant");
    }

    @Test
    void anOverrideAReleaseChangedUnderneathIsListedWithTheOldOriginalTheNewOneAndItself() throws Exception {
        PluginJars.smp(
                configs.resolve("smp/smp-0.9.1.jar"),
                java.util.Map.of(
                        "messages/smp/en.properties", "welcome=Welcome\nfarewell=Bye\n",
                        "messages/smp/de.properties", "welcome=Willkommen\n"));
        store.change("smp", "welcome", "de", List.of("Servus"), List.of("Willkommen alt"), Actor.STEWARD);
        store.change("smp", "farewell", "en", List.of("Cheers"), List.of("Bye"), Actor.STEWARD);

        final JsonArray fallbacks = GSON.fromJson(get("/api/message-fallbacks"), JsonArray.class);

        assertEquals(
                1, fallbacks.size(), "the override written over the text the jar still ships is shown: " + fallbacks);
        final JsonObject stale = fallbacks.get(0).getAsJsonObject();
        assertEquals("welcome", stale.get("key").getAsString());
        assertEquals("STALE", stale.get("reason").getAsString());
        assertEquals("[\"Willkommen alt\"]", stale.get("original").toString());
        assertEquals("[\"Willkommen\"]", stale.get("packaged").toString());
        assertEquals("[\"Servus\"]", stale.get("override").toString());
    }

    @Test
    void savingALineCreatesTheOverrideAndWarnsButStillSavesWhenAPlaceholderIsDropped() throws Exception {
        PluginJars.smp(
                configs.resolve("smp/smp-0.9.1.jar"),
                java.util.Map.of(
                        "messages/smp/en.properties", "greeting=Hello {player}\n",
                        "messages/smp/schema.json", """
                        {"bundle": "smp", "messages": [{"key": "greeting", "name": "Greeting",
                          "args": [{"name": "player", "kind": "text", "example": "Alex", "action": false}],
                          "section": [], "format": "MINIMESSAGE", "shown": ["CHAT"]}],
                         "contexts": {}, "globals": []}
                        """));

        final JsonObject saved = GSON.fromJson(
                put("/api/messages", "{\"changes\":{\"smp\":{\"greeting\":{\"en\":[\"Hello there\"]}}}}"),
                JsonObject.class);

        final JsonObject warning = saved.getAsJsonArray("warnings").get(0).getAsJsonObject();
        assertEquals("greeting", warning.get("key").getAsString(), saved.toString());
        assertEquals("en", warning.get("language").getAsString(), saved.toString());
        assertEquals(
                "check.value.unshown",
                warning.getAsJsonObject("text").get("key").getAsString(),
                saved.toString());
        assertEquals(
                "Hello there",
                texts(entry(saved.getAsJsonObject("texts"), "smp", "greeting"), "overrides", "en")
                        .getFirst(),
                "a warning must not stop the save");
    }

    @Test
    void aPlaceholderTheSchemaDoesNotDeclareIsRefusedWithTheKeyAndTheCheckSaysWhy() throws Exception {
        PluginJars.smp(
                configs.resolve("smp/smp-0.9.1.jar"),
                java.util.Map.of(
                        "messages/smp/en.properties", "greeting=Hello {player}\n",
                        "messages/smp/schema.json", """
                        {"bundle": "smp", "messages": [{"key": "greeting", "name": "Greeting",
                          "args": [{"name": "player", "kind": "text", "example": "Alex", "action": false}],
                          "section": ["Join"], "format": "MINIMESSAGE", "shown": ["CHAT"]}],
                         "contexts": {}, "globals": []}
                        """));

        final HttpResponse<String> refused =
                send("PUT", "/api/messages", "{\"changes\":{\"smp\":{\"greeting\":{\"en\":[\"Hello {name}\"]}}}}");

        assertEquals(400, refused.statusCode(), refused.body());
        assertTrue(refused.body().contains("greeting"), refused.body());
        assertEquals(List.of(), store.overrides(Set.of("smp")), "a refused save must not write anything");
        final JsonObject problem = GSON.fromJson(
                        get("/api/message-check?bundle=smp/smp&key=greeting&text=Hello%20%7Bname%7D"), JsonArray.class)
                .get(0)
                .getAsJsonObject();
        assertTrue(problem.get("error").getAsBoolean(), problem.toString());
        assertEquals(
                "check.value.unknown",
                problem.getAsJsonObject("text").get("key").getAsString());
        final JsonObject greeting = entry(GSON.fromJson(get("/api/messages"), JsonObject.class), "smp", "greeting");
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
    void aPreviewIsTheTriedTextAsThePlaceAskedForWithEveryValueTyped() throws Exception {
        greetingShownInThreePlaces();

        final JsonObject texts = GSON.fromJson(get("/api/messages"), JsonObject.class);
        assertEquals(
                GSON.fromJson("{\"TITLE\": \"GAME\", \"DISCORD_EMBED\": \"DISCORD\"}", JsonObject.class),
                text(texts, "smp", "greeting").getAsJsonObject("previews"));
        assertEquals(
                GSON.fromJson("{\"DISCORD_EMBED\": \"DISCORD\"}", JsonObject.class),
                text(texts, "smp", "link").getAsJsonObject("previews"));
        assertEquals(new JsonObject(), text(texts, "smp", "page").getAsJsonObject("previews"));

        final HttpResponse<String> asked = send("POST", "/api/message-preview", """
                {"bundle": "smp/smp", "key": "greeting", "language": "de", "text": "Moin {player} nach {time}",
                 "shown": "TITLE", "values": {"player": "Alex", "time": "eine Weile"}}
                """);
        assertEquals(204, asked.statusCode(), asked.body());
        assertEquals(Display.TITLE, previewed.shown());
        assertEquals("de", previewed.language());
        assertEquals("Moin {player} nach {time}", previewed.text());
        assertEquals("Alex", ((DisplayName) previewed.message().args().get("player")).name());
        assertEquals(
                Duration.ofMinutes(5),
                previewed.message().args().get("time"),
                "a value that does not read as its kind is the schema's example");
    }

    @Test
    void aPreviewGoesOnlyToAPlaceOfTheKeyThatAPreviewReaches() throws Exception {
        greetingShownInThreePlaces();

        final HttpResponse<String> nowhere = send(
                "POST",
                "/api/message-preview",
                "{\"bundle\": \"smp/smp\", \"key\": \"page\", \"language\": \"en\", \"text\": \"Page\","
                        + " \"shown\": \"STEWARD\"}");
        assertEquals(400, nowhere.statusCode(), nowhere.body());
        final HttpResponse<String> inDiscord = send("POST", "/api/message-preview", """
                {"bundle": "smp/smp", "key": "greeting", "language": "en", "text": "Hi {player}",
                 "shown": "DISCORD_EMBED"}
                """);
        assertEquals(204, inDiscord.statusCode(), inDiscord.body());
        assertEquals(Display.DISCORD_EMBED, previewed.shown(), "the same key previews as each of its places");
        final HttpResponse<String> notThere = send("POST", "/api/message-preview", """
                {"bundle": "smp/smp", "key": "greeting", "language": "en", "text": "Hi {player}", "shown": "STEWARD"}
                """);
        assertEquals(400, notThere.statusCode(), notThere.body());
        final HttpResponse<String> notItsPlace = send("POST", "/api/message-preview", """
                {"bundle": "smp/smp", "key": "greeting", "language": "en", "text": "Hi {player}", "shown": "GUI"}
                """);
        assertEquals(400, notItsPlace.statusCode(), notItsPlace.body());
        final HttpResponse<String> refused = send(
                "POST",
                "/api/message-preview",
                "{\"bundle\": \"smp/smp\", \"key\": \"greeting\", \"language\": \"en\", \"text\": \"Hi {name}\","
                        + " \"shown\": \"TITLE\"}");
        assertEquals(400, refused.statusCode(), refused.body());
        assertTrue(refused.body().contains("greeting"), refused.body());
    }

    /** A language the network setting adds is offered before any bundle ships it, and tones are each service's own. */
    @Test
    void theTextsNameTheNetworksLanguagesAndEachServicesColoursAndAPreviewCarriesTheOneAskedFor() throws Exception {
        PluginJars.smp(
                configs.resolve("smp/smp-0.9.1.jar"),
                java.util.Map.of(
                        "messages/smp/en.properties", "greeting=Hello\n",
                        "messages/smp/schema.json", """
                        {"bundle": "smp", "messages": [
                          {"key": "greeting", "name": "Greeting", "section": [], "format": "MINIMESSAGE",
                           "shown": ["CHAT"], "args": []}],
                         "contexts": {}, "globals": []}
                        """));
        settings.change(
                SettingStore.NETWORK,
                "language-and-time",
                java.util.Map.of("languages", "[\"de\", \"en\", \"nl\"]"),
                Actor.STEWARD,
                current -> true);
        settings.change("smp", "colours", java.util.Map.of("bad", "\"#123456\""), Actor.STEWARD, current -> true);

        final JsonObject texts = GSON.fromJson(get("/api/messages"), JsonObject.class);
        assertEquals(GSON.fromJson("[\"en\", \"de\", \"nl\"]", JsonArray.class), texts.getAsJsonArray("languages"));
        final JsonObject smp = texts.getAsJsonObject("colours").getAsJsonObject("smp");
        assertEquals("#123456", smp.get("bad").getAsString());
        assertEquals(Tone.GOOD.hex(), smp.get("good").getAsString());

        send("POST", "/api/message-preview", """
                {"bundle": "smp/smp", "key": "greeting", "language": "nl", "text": "<bad>Hallo</bad>", "shown": "CHAT",
                 "service": "smp"}
                """);
        assertEquals("#123456", previewed.colours().get("bad"), "the palette of the service the page is filtered to");
        send("POST", "/api/message-preview", """
                {"bundle": "smp/smp", "key": "greeting", "language": "nl", "text": "<bad>Hallo</bad>", "shown": "CHAT"}
                """);
        assertEquals(Tone.BAD.hex(), previewed.colours().get("bad"), "else the network's");
    }

    @Test
    void resettingAKeyRemovesItFromTheOverrideRatherThanCopyingEnglishIntoIt() throws Exception {
        PluginJars.smp(
                configs.resolve("smp/smp-0.9.1.jar"),
                java.util.Map.of("messages/smp/en.properties", "welcome=Welcome\n"));
        put("/api/messages", "{\"changes\":{\"smp\":{\"welcome\":{\"en\":[\"Howdy\"]}}}}");

        final JsonObject afterReset = GSON.fromJson(
                put("/api/messages", "{\"changes\":{\"smp\":{\"welcome\":{\"en\":null}}}}"), JsonObject.class);

        assertFalse(
                entry(afterReset.getAsJsonObject("texts"), "smp", "welcome")
                        .getAsJsonObject("overrides")
                        .has("en"),
                afterReset.toString());
        assertTrue(afterReset.getAsJsonArray("warnings").isEmpty());
    }

    @Test
    void aKeyEveryJarShipsIsOneTextWithEveryServiceThatShowsIt() throws Exception {
        PluginJars.smp(
                configs.resolve("smp/smp-0.9.1.jar"),
                java.util.Map.of(
                        "messages/smp/en.properties", "welcome=Welcome\n",
                        "messages/paper-common/en.properties", "reload.done=Reloaded\n"));
        PluginJars.write(
                configs.resolve("limbo/limbo-0.9.1.jar"),
                "{\"id\": \"limbo\", \"messages\": true}",
                java.util.Map.of(
                        "messages/limbo/en.properties", "waiting=Waiting\n",
                        "messages/paper-common/en.properties", "reload.done=Reloaded\n"));

        final JsonObject texts = GSON.fromJson(get("/api/messages"), JsonObject.class);

        assertEquals(3, texts.getAsJsonArray("texts").size(), texts.toString());
        assertEquals(
                GSON.fromJson("[\"limbo\", \"smp\"]", JsonArray.class),
                text(texts, "paper-common", "reload.done").getAsJsonArray("services"));
        assertEquals(
                "limbo/limbo",
                text(texts, "paper-common", "reload.done").get("path").getAsString());
        assertEquals(
                GSON.fromJson("[\"smp\"]", JsonArray.class),
                text(texts, "smp", "welcome").getAsJsonArray("services"));
    }

    @Test
    void aTextIsKeptOnlyForTheServicesThatDrawOneOfItsPlaces() throws Exception {
        final String admin = """
                {"bundle": "admin", "messages": [{"key": "alert.disk", "name": "A full disk", "args": [],
                  "section": [], "format": "PLAIN", "shown": ["STEWARD", "PUSH"]}],
                 "contexts": {}, "globals": []}
                """;
        PluginJars.smp(
                configs.resolve("smp/smp-0.9.1.jar"),
                java.util.Map.of(
                        "messages/admin/en.properties", "alert.disk=The disk is full\n",
                        "messages/admin/schema.json", admin,
                        "messages/values/en.properties", "missing.name=someone\n"));
        PluginJars.write(
                configs.resolve("limbo/limbo-0.9.1.jar"),
                "{\"id\": \"limbo\", \"messages\": true}",
                java.util.Map.of(
                        "messages/admin/en.properties", "alert.disk=The disk is full\n",
                        "messages/admin/schema.json", admin,
                        "messages/values/en.properties", "missing.name=someone\n"));

        final JsonObject texts = GSON.fromJson(get("/api/messages"), JsonObject.class);

        assertEquals(
                new JsonArray(),
                text(texts, "admin", "alert.disk").getAsJsonArray("services"),
                "no game server draws Steward's page or its notifications");
        assertEquals(
                GSON.fromJson("[\"limbo\", \"smp\"]", JsonArray.class),
                text(texts, "values", "missing.name").getAsJsonArray("services"));
    }

    @Test
    void theTextsNameEveryPlaceInOrderWithWhereItIs() throws Exception {
        final JsonObject places =
                GSON.fromJson(get("/api/messages"), JsonObject.class).getAsJsonObject("places");

        assertEquals(Arrays.stream(Display.values()).map(Display::name).toList(), List.copyOf(places.keySet()));
        assertEquals("GAME", places.get("CHAT").getAsString());
        assertEquals("DISCORD", places.get("DISCORD_BUTTON").getAsString());
        assertEquals("STEWARD", places.get("PUSH").getAsString());
    }

    @Test
    void twoBundlesThatDeclareTheSameKeyAreTwoTexts() throws Exception {
        PluginJars.smp(
                configs.resolve("smp/smp-0.9.1.jar"),
                java.util.Map.of("messages/paper-common/en.properties", "command.unknown=Unknown here\n"));
        PluginJars.write(
                configs.resolve("proxy/proxy-0.9.1.jar"),
                "{\"id\": \"proxy\", \"messages\": true}",
                java.util.Map.of("messages/proxy/en.properties", "command.unknown=Unknown everywhere\n"));

        final JsonObject texts = GSON.fromJson(get("/api/messages"), JsonObject.class);

        assertEquals(List.of("Unknown here"), texts(entry(texts, "paper-common", "command.unknown"), "texts", "en"));
        assertEquals(List.of("Unknown everywhere"), texts(entry(texts, "proxy", "command.unknown"), "texts", "en"));
    }

    @Test
    void oneSaveWritesTheTextsOfSeveralBundlesAndNamesEachForTheJournal() throws Exception {
        PluginJars.smp(
                configs.resolve("smp/smp-0.9.1.jar"),
                java.util.Map.of(
                        "messages/smp/en.properties", "welcome=Welcome\n",
                        "messages/paper-common/en.properties", "reload.done=Reloaded\n"));

        put(
                "/api/messages",
                "{\"changes\":{\"smp\":{\"welcome\":{\"en\":[\"Howdy\"]}},"
                        + "\"paper-common\":{\"reload.done\":{\"en\":[\"Done\"]}}}}");

        assertEquals(List.of("smp", "paper-common"), journalled);
        assertEquals(1, store.overrides(Set.of("smp")).size());
        assertEquals(1, store.overrides(Set.of("paper-common")).size());
    }

    @Test
    void bothLanguagesAreSavedInOneCall() throws Exception {
        smpBundle();

        final JsonObject saved = GSON.fromJson(
                put("/api/messages", "{\"changes\":{\"smp\":{\"welcome\":{\"en\":[\"Howdy\"],\"de\":[\"Servus\"]}}}}"),
                JsonObject.class);

        assertEquals(
                List.of("Howdy"),
                texts(entry(saved.getAsJsonObject("texts"), "smp", "welcome"), "overrides", "en"),
                saved.toString());
        assertEquals(
                List.of("Servus"),
                texts(entry(saved.getAsJsonObject("texts"), "smp", "welcome"), "overrides", "de"),
                saved.toString());
    }

    @Test
    void aSaveIsARowUnderThePackagedBundleOfItsKeyAndAppliesAtOnce() throws Exception {
        PluginJars.smp(
                configs.resolve("smp/smp-0.9.1.jar"),
                java.util.Map.of(
                        "messages/smp/en.properties", "welcome=Welcome\n",
                        "messages/paper-common/en.properties", "reload.done=Reloaded\n"));

        final JsonObject saved = GSON.fromJson(
                put("/api/messages", "{\"changes\":{\"paper-common\":{\"reload.done\":{\"en\":[\"Done\"]}}}}"),
                JsonObject.class);

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
                send("PUT", "/api/messages", "{\"changes\":{\"smp\":{\"welcom\":{\"en\":[\"Hi\"]}}}}");

        assertEquals(400, refused.statusCode(), refused.body());
        assertTrue(refused.body().contains("welcom"), refused.body());
        assertEquals(List.of(), store.overrides(Set.of("smp")));
    }

    /** A language's variants under {@code field}, {@code texts} or {@code overrides}, of one entry. */
    private static List<String> texts(final JsonObject entry, final String field, final String language) {
        final JsonArray variants = entry.getAsJsonObject(field).getAsJsonArray(language);
        final List<String> texts = new java.util.ArrayList<>();
        variants.forEach(variant -> texts.add(variant.getAsString()));
        return texts;
    }

    /** {@code greeting} is shown in a title, a Discord embed and Steward; {@code page} only in Steward. */
    private void greetingShownInThreePlaces() throws IOException {
        PluginJars.smp(
                configs.resolve("smp/smp-0.9.1.jar"),
                java.util.Map.of(
                        "messages/smp/en.properties", "greeting=Hello {player} after {time}\npage=Page\nlink=Link\n",
                        "messages/smp/schema.json", """
                        {"bundle": "smp", "messages": [
                          {"key": "greeting", "name": "Greeting", "section": ["Join"], "format": "MINIMESSAGE",
                           "shown": ["TITLE", "DISCORD_EMBED", "STEWARD"], "args": [
                             {"name": "player", "kind": "name", "example": "Alex", "action": false},
                             {"name": "time", "kind": "duration", "example": "PT5M", "action": false}]},
                          {"key": "page", "name": "Page", "section": [], "format": "PLAIN", "shown": ["STEWARD"],
                           "args": []},
                          {"key": "link", "name": "Link", "section": [], "format": "DISCORD_MARKDOWN",
                           "shown": ["DISCORD_EMBED"], "args": []}],
                         "contexts": {}, "globals": []}
                        """));
    }

    private void smpBundle() throws IOException {
        PluginJars.smp(
                configs.resolve("smp/smp-0.9.1.jar"),
                java.util.Map.of("messages/smp/en.properties", "welcome=Welcome\n"));
    }

    /** One text of {@code GET /api/messages}: its entry, where it is read from, who shows it, its previews. */
    private static JsonObject text(final JsonObject texts, final String bundle, final String key) {
        for (final var element : texts.getAsJsonArray("texts")) {
            final JsonObject row = element.getAsJsonObject();
            final JsonObject entry = row.getAsJsonObject("entry");
            if (bundle.equals(entry.get("bundle").getAsString())
                    && key.equals(entry.get("key").getAsString())) {
                return row;
            }
        }
        throw new AssertionError("no key " + bundle + "/" + key + " in " + texts);
    }

    private static JsonObject entry(final JsonObject texts, final String bundle, final String key) {
        return text(texts, bundle, key).getAsJsonObject("entry");
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
}
