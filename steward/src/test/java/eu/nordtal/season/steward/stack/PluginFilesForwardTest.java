package eu.nordtal.season.steward.stack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import eu.nordtal.season.common.Sha256;
import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.common.json.Json;
import eu.nordtal.season.common.time.TestScheduler;
import eu.nordtal.season.database.JournalDetail;
import eu.nordtal.season.database.audit.AuditDirectory;
import eu.nordtal.season.database.audit.AuditEntry;
import eu.nordtal.season.database.audit.AuditLine;
import eu.nordtal.season.database.audit.JournalAction;
import eu.nordtal.season.database.audit.JournalFilter;
import eu.nordtal.season.internalapi.agent.AgentClient;
import eu.nordtal.season.internalapi.agent.AgentWire;
import eu.nordtal.season.messages.Messages;
import eu.nordtal.season.steward.ErrorHandlers;
import eu.nordtal.season.steward.journal.Journaling;
import eu.nordtal.season.steward.settings.ServerTrial;
import eu.nordtal.season.steward.texts.WebTexts;
import eu.nordtal.season.stewardagent.AgentStandIn;
import eu.nordtal.season.stewardagent.plan.PluginFiles;
import io.javalin.Javalin;
import io.javalin.json.JavalinGson;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The plugin file routes from the browser's side: forwarded to the agent, a conflict kept, a save journaled. */
class PluginFilesForwardTest {

    @TempDir
    Path scratch;

    private AgentStandIn agent;
    private Javalin server;
    private StackApi api;
    private Path config;
    private final List<AuditLine> journal = new ArrayList<>();
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void start() throws IOException {
        config = scratch.resolve("volumes/smp/plugins/CoreProtect/config.yml");
        Files.createDirectories(config.getParent());
        Files.writeString(config, "use-mysql: false\n");
        final List<AgentWire.Plugin> rows = List.of(new AgentWire.Plugin(
                "CoreProtect",
                AgentWire.PluginGroup.ADDED,
                null,
                true,
                true,
                null,
                null,
                null,
                null,
                "CoreProtect",
                null,
                null,
                null,
                null,
                null,
                null));
        final PluginFiles files = new PluginFiles(
                service -> new AgentWire.Plugins(service, "paper", "1.21.10", true, rows), scratch.resolve("volumes"));
        agent = new AgentStandIn(scratch, 0, cfg -> {
            cfg.routes.get(AgentWire.PLUGIN_FILES, files::list);
            cfg.routes.get(AgentWire.PLUGIN_FILE, files::read);
            cfg.routes.post(AgentWire.PLUGIN_FILE, files::save);
        });
        final AgentClient client = new AgentClient(agent.client());
        api = new StackApi(
                client,
                FakeDirectories.updates(),
                FakeDirectories.audit(),
                () -> new StackApi.Nightly("04:45", List.of(), "05:15", List.of(), ZoneId.of("Europe/Berlin")),
                null,
                null,
                new PluginsForward(client, FakeDirectories.updates()),
                null,
                null,
                null,
                ServerTrial.NONE,
                Clock.systemUTC(),
                TestScheduler.SHARED);
        final Journaling journaling = new Journaling(recording(), ctx -> Optional.of(Actor.STEWARD));
        server = Javalin.create(cfg -> {
                    cfg.jsonMapper(new JavalinGson(Json.gson(), true));
                    cfg.startup.showJavalinBanner = false;
                    ErrorHandlers.install(cfg, WebTexts.load().messages(), Messages.load("messages/database"));
                    api.register(cfg, StackServer.ALWAYS);
                    cfg.routes.afterMatched(journaling::write);
                })
                .start(0);
    }

    @AfterEach
    void stop() throws IOException {
        server.stop();
        api.close();
        agent.close();
    }

    private AuditDirectory recording() {
        return new AuditDirectory() {
            @Override
            public List<AuditEntry> recent(final int limit) {
                return List.of();
            }

            @Override
            public List<AuditEntry> search(final JournalFilter filter, final int limit) {
                return List.of();
            }

            @Override
            public Optional<AuditEntry> entry(final UUID id) {
                return Optional.empty();
            }

            @Override
            public void record(final AuditLine line) {
                journal.add(line);
            }
        };
    }

    private HttpResponse<String> send(final String method, final String path, final String body) throws Exception {
        return http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                        .method(
                                method,
                                body == null
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static final String FILE = "/api/services/smp/plugin-file?plugin=CoreProtect&path=config.yml";

    @Test
    void theListAndTheTextComeThroughFromTheAgent() throws Exception {
        final JsonObject list = JsonParser.parseString(
                        send("GET", "/api/services/smp/plugin-files", null).body())
                .getAsJsonObject();
        assertEquals(
                "config.yml",
                list.getAsJsonArray("files")
                        .get(0)
                        .getAsJsonObject()
                        .get("path")
                        .getAsString());

        final JsonObject document =
                JsonParser.parseString(send("GET", FILE, null).body()).getAsJsonObject();
        assertEquals("use-mysql: false\n", document.get("text").getAsString());
        assertEquals(
                "use-mysql",
                document.getAsJsonArray("entries")
                        .get(0)
                        .getAsJsonObject()
                        .get("path")
                        .getAsString());
    }

    @Test
    void theFormsChangesAreWrittenIntoTheTextOnDisk() throws Exception {
        Files.writeString(config, "# kept\nuse-mysql: false # kept\n");
        final String body = "{\"changes\":{\"use-mysql\":\"true\"},\"revision\":\""
                + Sha256.hex("# kept\nuse-mysql: false # kept\n") + "\"}";

        final HttpResponse<String> saved = send("PUT", FILE, body);

        assertEquals(200, saved.statusCode(), saved.body());
        assertEquals("# kept\nuse-mysql: true # kept\n", Files.readString(config));
    }

    @Test
    void theFormsChangesAgainstAnOlderRevisionAreA409() throws Exception {
        final HttpResponse<String> refused = send(
                "PUT", FILE, "{\"changes\":{\"use-mysql\":\"true\"},\"revision\":\"" + Sha256.hex("older") + "\"}");

        assertEquals(409, refused.statusCode());
        assertEquals("use-mysql: false\n", Files.readString(config));
    }

    @Test
    void aSaveWritesTheFileAndIsJournaledAsASettingsSave() throws Exception {
        final String body =
                "{\"text\":\"use-mysql: true\\n\",\"revision\":\"" + Sha256.hex("use-mysql: false\n") + "\"}";

        final HttpResponse<String> saved = send("PUT", FILE, body);

        assertEquals(200, saved.statusCode(), saved.body());
        assertEquals(
                "RESTART_REQUIRED",
                JsonParser.parseString(saved.body())
                        .getAsJsonObject()
                        .getAsJsonObject("reload")
                        .get("status")
                        .getAsString());
        assertEquals("use-mysql: true\n", Files.readString(config));
        assertEquals(1, journal.size());
        assertEquals(JournalAction.SAVE_SETTINGS, journal.getFirst().action());
        assertEquals(
                new JournalDetail.SettingsSaved("smp", "CoreProtect/config.yml"),
                journal.getFirst().detail());
    }

    @Test
    void aConflictReachesTheBrowserAsA409AndIsNotJournaled() throws Exception {
        final HttpResponse<String> refused =
                send("PUT", FILE, "{\"text\":\"use-mysql: true\\n\",\"revision\":\"" + Sha256.hex("older") + "\"}");

        assertEquals(409, refused.statusCode());
        assertEquals("use-mysql: false\n", Files.readString(config));
        assertTrue(journal.isEmpty());
    }

    @Test
    void aFileNotInTheListIsA404() throws Exception {
        assertEquals(
                404,
                send("GET", "/api/services/smp/plugin-file?plugin=CoreProtect&path=../../server.properties", null)
                        .statusCode());
    }
}
