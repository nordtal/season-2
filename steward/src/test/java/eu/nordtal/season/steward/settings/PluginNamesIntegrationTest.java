package eu.nordtal.season.steward.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.database.TestDatabase;
import eu.nordtal.season.database.message.MessageOverrideStore;
import eu.nordtal.season.internalapi.agent.AgentClient;
import eu.nordtal.season.stewardagent.AgentStandIn;
import eu.nordtal.season.stewardagent.PluginJars;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** That a choice's values are named by the texts of the plugin that published the group, as an admin changed them. */
class PluginNamesIntegrationTest {

    private Path scratch;
    private AgentStandIn agent;
    private MessageOverrideStore overrides;

    @BeforeEach
    void start() throws IOException {
        // Short, since a Unix socket path has a length limit the default temp directory can exceed.
        scratch = Files.createTempDirectory(Path.of("/tmp"), "names");
        agent = new AgentStandIn(scratch, 0, config -> {});
        overrides = MessageOverrideStore.using(TestDatabase.fresh().dataSource());
        PluginJars.smp(
                agent.configs.resolve("smp/smp-0.9.1.jar"),
                Map.of(
                        "messages/smp/en.properties",
                        "smp.settings.unlock.border=<accent>Border</accent>\nsmp.settings.unlock.nether=Nether\n",
                        "messages/smp/de.properties",
                        "smp.settings.unlock.border=Grenze\n"));
    }

    @AfterEach
    void stop() throws IOException {
        agent.close();
        try (Stream<Path> files = Files.walk(scratch)) {
            files.sorted(Comparator.reverseOrder())
                    .forEach(path -> path.toFile().delete());
        }
    }

    @Test
    void aValueIsNamedByThePluginsEnglishTextWithoutItsMarkup() {
        final SettingsDocument.Names names = PluginNames.of(new AgentClient(agent.client()), overrides, "smp");

        assertEquals("Border", names.name("smp.settings.unlock.border"));
        assertNull(names.name("smp.settings.unlock.end"), "a key the bundle does not have names nothing");
    }

    @Test
    void anAdminsOverrideNamesItInstead() {
        overrides.change(
                "smp", "smp.settings.unlock.nether", "en", List.of("The Nether"), List.of("Nether"), Actor.STEWARD);

        final SettingsDocument.Names names = PluginNames.of(new AgentClient(agent.client()), overrides, "smp");

        assertEquals("The Nether", names.name("smp.settings.unlock.nether"));
    }

    @Test
    void aServiceWithoutAJarNamesNothing() {
        final SettingsDocument.Names names = PluginNames.of(new AgentClient(agent.client()), overrides, "network");

        assertNull(names.name("smp.settings.unlock.border"));
    }
}
