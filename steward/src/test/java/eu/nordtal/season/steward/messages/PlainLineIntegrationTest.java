package eu.nordtal.season.steward.messages;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonObject;
import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.database.TestDatabase;
import eu.nordtal.season.database.document.DocumentStore;
import eu.nordtal.season.database.message.MessageOverrideStore;
import eu.nordtal.season.database.setting.SettingStore;
import eu.nordtal.season.internalapi.agent.AgentClient;
import eu.nordtal.season.messages.document.Document;
import eu.nordtal.season.messages.document.Documents;
import eu.nordtal.season.stewardagent.AgentStandIn;
import eu.nordtal.season.stewardagent.PluginJars;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.function.Function;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests {@link DocumentsApi#plainLine} against a database, as Steward names the milestones. */
class PlainLineIntegrationTest {

    private static final String DECLARATIONS = """
            [{"namespace":"smp","name":"milestone-name","title":"Milestone names","area":"Milestones",
              "shape":"BOSS_BAR","values":{"milestone":{"kind":"text","example":"foothold"}},"slots":[]}]""";

    private static final String NAMES = """
            {"parts":[{"type":"TEXT","lines":[
              {"when":"milestone=foothold","text":{"en":"Foothold","de":"Fuß fassen"}}]}]}""";

    private Path scratch;
    private AgentStandIn agent;
    private DocumentStore store;
    private DocumentsApi documents;

    @BeforeEach
    void start() throws IOException {
        // Short, because a Unix socket path has a length limit.
        scratch = Files.createTempDirectory(Path.of("/tmp"), "plain");
        agent = new AgentStandIn(scratch, 0, config -> {}, new JsonObject());
        PluginJars.smp(
                agent.configs.resolve("smp/smp-0.9.1.jar"),
                Map.of(
                        "documents/smp/declarations.json", DECLARATIONS,
                        "documents/smp/milestone-name.json", NAMES));
        final DataSource database = TestDatabase.fresh().dataSource();
        store = DocumentStore.using(database);
        documents = new DocumentsApi(
                new AgentClient(agent.client()),
                store,
                SettingStore.using(database),
                MessageOverrideStore.using(database));
    }

    @AfterEach
    void stop() throws IOException {
        agent.close();
        try (var files = Files.walk(scratch)) {
            files.sorted(Comparator.reverseOrder())
                    .forEach(path -> path.toFile().delete());
        }
    }

    @Test
    void aShippedLineNamesItsIdInEnglishAndAnyOtherIdIsBlank() {
        final Function<Map<String, Object>, String> names = documents.plainLine("smp", "milestone-name");

        assertEquals("Foothold", names.apply(Map.of("milestone", "foothold")));
        assertEquals("", names.apply(Map.of("milestone", "harbour")));
        assertEquals("", documents.plainLine("smp", "nothing").apply(Map.of("milestone", "foothold")));
    }

    @Test
    void aLineAnAdminAddsNamesTheMilestoneTheyAdded() {
        final Documents.Packaged packaged =
                new Documents.Packaged(Documents.decode(DECLARATIONS).getFirst(), Document.decode(NAMES));
        store.change(
                new DocumentStore.StoredDocument("smp", "milestone-name", Document.decode("""
                                {"parts":[{"type":"TEXT","lines":[
                                  {"when":"milestone=foothold","text":{"en":"Foothold","de":"Fuß fassen"}},
                                  {"when":"milestone=Harbour_2","text":{"en":"Harbour","de":"Hafen"}}]}]}"""), packaged.hash()),
                Actor.STEWARD);

        final Function<Map<String, Object>, String> names = documents.plainLine("smp", "milestone-name");

        assertEquals("Harbour", names.apply(Map.of("milestone", "Harbour_2")));
        assertEquals("Foothold", names.apply(Map.of("milestone", "foothold")));
    }
}
