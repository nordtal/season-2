package eu.nordtal.season.steward.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.database.inbox.ServerRefusal;
import eu.nordtal.season.database.message.MessageOverrideStore;
import eu.nordtal.season.database.setting.SettingRule;
import eu.nordtal.season.internalapi.InternalClient;
import eu.nordtal.season.internalapi.agent.AgentClient;
import eu.nordtal.season.internalapi.agent.AgentWire;
import eu.nordtal.season.messages.Messages;
import eu.nordtal.season.settings.Environment;
import eu.nordtal.season.settings.Group;
import eu.nordtal.season.settings.MemorySettingStore;
import eu.nordtal.season.steward.ErrorHandlers;
import eu.nordtal.season.steward.WireJson;
import eu.nordtal.season.steward.texts.WebTexts;
import io.javalin.Javalin;
import io.javalin.json.JavalinGson;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Tests that a save is refused by the rules the spec declares before the server is asked, and how it is answered. */
class SettingsApiTest {

    private static final Group<ExampleGroupSpec> EXAMPLE = Group.of("example", ExampleGroupSpec.class);

    private final MemorySettingStore store = new MemorySettingStore();
    private final List<String> asked = new ArrayList<>();
    private ServerTrial.Verdict verdict = new ServerTrial.Verdict(ServerTrial.Verdict.Kind.TAKEN, null);
    private Javalin web = Javalin.create();

    @AfterEach
    void stop() {
        web.stop();
    }

    @Test
    void aBrokenRuleIsRefusedWithItsSettingAndRuleBeforeTheServerIsAsked() throws Exception {
        store.settings("smp").load(EXAMPLE);

        final HttpResponse<String> answer = save("{\"max-players\": 0}");

        assertEquals(422, answer.statusCode());
        final JsonObject body = JsonParser.parseString(answer.body()).getAsJsonObject();
        assertEquals("SETTINGS_REFUSED", body.get("code").getAsString());
        assertEquals(
                JsonParser.parseString("{\"setting\":\"max-players\",\"entries\":[],\"rule\":\"POSITIVE\"}"),
                body.get("refusal"));
        assertTrue(body.get("error").getAsString().contains("max-players must be greater than zero"), answer.body());
        assertEquals(List.of(), asked, "the server is not asked about a change its rules refuse");
        assertEquals(List.of(), store.overrides(List.of("smp")));
    }

    @Test
    void aBreachInAnEntryNamesTheEntryByItsIndex() throws Exception {
        store.settings("smp").load(EXAMPLE);

        final HttpResponse<String> answer =
                save("{\"stages\": [{\"key\": \"a\"}, {\"key\": \"b\"}, {\"key\": \"a\"}]}");

        assertEquals(422, answer.statusCode());
        final JsonObject body = JsonParser.parseString(answer.body()).getAsJsonObject();
        assertEquals(
                JsonParser.parseString(
                        "{\"setting\":\"stages.key\",\"entries\":[2],\"rule\":\"UNIQUE\",\"value\":\"a\"}"),
                body.get("refusal"));
        assertTrue(body.get("error").getAsString().contains("stages[2].key holds a twice"), answer.body());
    }

    @Test
    void anEntryIsCheckedWithTheFieldsItLacksFromItsTemplateAsTheServerReadsIt() throws Exception {
        store.settings("smp").load(EXAMPLE);

        final HttpResponse<String> answer = save("{\"stages\": [{\"kind\": \"GIVEN\"}, {\"kind\": \"GIVEN\"}]}");

        assertEquals(422, answer.statusCode());
        assertEquals(
                JsonParser.parseString(
                        "{\"setting\":\"stages.key\",\"entries\":[1],\"rule\":\"UNIQUE\",\"value\":\"\"}"),
                JsonParser.parseString(answer.body()).getAsJsonObject().get("refusal"));
    }

    @Test
    void aSettingTheEnvironmentHoldsIsLeftToTheServer() throws Exception {
        store.settings(
                        "smp",
                        Environment.of("NORDTAL").withMain("example").reading(Map.of("NORDTAL_MAX_PLAYERS", "5")::get))
                .load(EXAMPLE);

        assertEquals(200, save("{\"max-players\": 0}").statusCode());
        assertEquals(List.of("smp"), asked);
    }

    @Test
    void theServersRefusalCarriesTheSettingAndRuleItNamed() throws Exception {
        store.settings("smp").load(EXAMPLE);
        verdict = new ServerTrial.Verdict(
                ServerTrial.Verdict.Kind.REFUSED,
                ServerRefusal.SETTINGS_REFUSED
                        .with("smp", "stages", SettingRule.OBJECTIVE_NAMES, "stone_axes")
                        .message());

        final HttpResponse<String> answer = save("{\"motd\": \"Welcome\"}");

        assertEquals(422, answer.statusCode());
        final JsonObject body = JsonParser.parseString(answer.body()).getAsJsonObject();
        assertEquals(
                JsonParser.parseString("{\"setting\":\"stages\",\"entries\":[],\"rule\":\"OBJECTIVE_NAMES\","
                        + "\"value\":\"stone_axes\"}"),
                body.get("refusal"));
        assertTrue(body.get("error").getAsString().contains("stone_axes names something"), answer.body());
    }

    @Test
    void aRefusalTheServerWroteInWordsCarriesNoRule() throws Exception {
        store.settings("smp").load(EXAMPLE);
        verdict = new ServerTrial.Verdict(
                ServerTrial.Verdict.Kind.REFUSED,
                ServerRefusal.SETTINGS_REFUSED
                        .with("smp", null, null, null, "the track cannot be read")
                        .message());

        final HttpResponse<String> answer = save("{\"motd\": \"Welcome\"}");

        assertEquals(422, answer.statusCode());
        final JsonObject body = JsonParser.parseString(answer.body()).getAsJsonObject();
        assertFalse(body.has("refusal"), answer.body());
        assertTrue(body.get("error").getAsString().contains("the track cannot be read"), answer.body());
    }

    private HttpResponse<String> save(final String changes) throws Exception {
        final String revision = SettingsDocument.revisionOf(
                store.overrides(List.of("smp")).stream().toList());
        final SettingsApi api = new SettingsApi(
                store,
                new AgentClient(new InternalClient(AgentWire.SERVICE, "http://127.0.0.1:1", "", Duration.ofSeconds(1))),
                unread(),
                (service, change, actor) -> {
                    asked.add(service);
                    return verdict;
                });
        web = Javalin.create(cfg -> {
                    cfg.jsonMapper(new JavalinGson(WireJson.gson(), true));
                    cfg.startup.showJavalinBanner = false;
                    ErrorHandlers.install(cfg, WebTexts.load().messages(), Messages.load("messages/database"));
                    cfg.routes.post("/settings/{service}/{name}", ctx -> api.save(ctx, Actor.STEWARD));
                })
                .start(0);
        final String body = "{\"revision\": \"" + revision + "\", \"changes\": " + changes + "}";
        return HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + web.port() + "/settings/smp/example"))
                                .POST(HttpRequest.BodyPublishers.ofString(body))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
    }

    /** A store of overrides that no refused save reads. */
    private static MessageOverrideStore unread() {
        return (MessageOverrideStore) Proxy.newProxyInstance(
                MessageOverrideStore.class.getClassLoader(),
                new Class<?>[] {MessageOverrideStore.class},
                (proxy, method, args) -> {
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
