package eu.nordtal.s2.stewardagent.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.database.TestDatabase;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Exercises {@link PluginDirectory} against a real PostgreSQL running the real migrations.
 *
 * A second Install must refresh {@code file_prefix} rather than fail; tests skip without Docker.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PluginDirectoryIntegrationTest {
    private static DataSource dataSource;

    private PluginDirectory plugins;

    @BeforeAll
    static void startDatabase() {
        dataSource = TestDatabase.fresh().dataSource();
    }

    @BeforeEach
    void freshTable() {
        execute("TRUNCATE TABLE service_plugin, plugin_file");
        plugins = PluginDirectory.using(dataSource);
    }

    private static ManagedPlugin row(final String service, final String slug, final String prefix) {
        return new ManagedPlugin(
                service,
                slug,
                "1u6JkXh5",
                prefix,
                "WorldEdit",
                "https://cdn.modrinth.com/data/1u6JkXh5/icon.png",
                "https://modrinth.com/plugin/" + slug,
                Instant.EPOCH,
                "till (1)");
    }

    @Test
    void aFileNamesTheReleaseThatInstalledItAndANewerFileReplacesItsNote() {
        plugins.installed("smp", "smp", "smp-0.10.3.jar", "0.10.3");
        plugins.installed("smp", "voicechat", "voicechat-bukkit-2.6.1.jar", "0.10.3");
        plugins.installed("smp", "smp", "smp-0.11.0.jar", "0.11.0");
        plugins.installed("limbo", "limbo", "limbo-0.11.0.jar", "0.11.0");

        assertEquals(
                java.util.Map.of("smp-0.11.0.jar", "0.11.0", "voicechat-bukkit-2.6.1.jar", "0.10.3"),
                plugins.releases("smp"));
    }

    @Test
    void removingAnAddedPluginForgetsItsFile() {
        plugins.add(row("smp", "worldedit", "worldedit-bukkit"));
        plugins.installed("smp", "worldedit", "worldedit-bukkit-7.3.jar", "0.11.0");

        plugins.remove("smp", "worldedit");

        assertEquals(java.util.Map.of(), plugins.releases("smp"));
    }

    @Test
    void aRowComesBackAsItWasWrittenWithTheDatabasesOwnClockOnIt() {
        plugins.add(row("smp", "worldedit", "worldedit-bukkit"));

        final List<ManagedPlugin> all = plugins.all();
        assertEquals(1, all.size());
        final ManagedPlugin written = all.getFirst();
        assertEquals("smp", written.service());
        assertEquals("worldedit", written.artifact());
        assertEquals("1u6JkXh5", written.projectId());
        assertEquals("worldedit-bukkit", written.filePrefix());
        assertEquals("WorldEdit", written.title());
        assertEquals("till (1)", written.addedBy());
        // `added` is the column default, so two processes with two clocks cannot disagree about it.
        assertTrue(written.added().isAfter(Instant.EPOCH), "added came from the caller, not from now()");
    }

    @Test
    void installingTwiceRefreshesTheRowRatherThanFailing() {
        plugins.add(row("smp", "worldedit", "worldedit-bukkit"));
        // The prefix changes when a project renames its artefact; removal must find the new jar.
        plugins.add(row("smp", "worldedit", "worldedit-paper"));

        assertEquals(1, plugins.all().size(), "the second press wrote a second row");
        assertEquals("worldedit-paper", plugins.all().getFirst().filePrefix());
    }

    @Test
    void theSamePluginOnTwoServicesIsTwoRowsAndEachServiceSeesOnlyItsOwn() {
        plugins.add(row("smp", "worldedit", "worldedit-bukkit"));
        plugins.add(row("hunger-games", "worldedit", "worldedit-bukkit"));

        assertEquals(2, plugins.all().size());
        assertEquals(
                List.of("worldedit"),
                plugins.on("smp").stream().map(ManagedPlugin::artifact).toList());
        assertEquals(1, plugins.on("hunger-games").size());
        assertEquals(0, plugins.on("limbo").size());
    }

    @Test
    void removingTakesOneRowAndRemovingItTwiceIsNotAnError() {
        plugins.add(row("smp", "worldedit", "worldedit-bukkit"));

        plugins.remove("smp", "worldedit");
        plugins.remove("smp", "worldedit");

        assertEquals(0, plugins.all().size());
    }

    @Test
    void aServiceNameTheRestOfTheStackCouldNotUseIsRefusedByTheCheck() {
        // The service alphabet: a row the resolver never matches would leave a plugin uninstalled silently.
        assertThrows(RuntimeException.class, () -> plugins.add(row("SMP", "worldedit", "worldedit-bukkit")));
    }

    @Test
    void anIconAndAPageAreAllowedToBeAbsentNotEveryProjectHasAPicture() {
        plugins.add(new ManagedPlugin("limbo", "thing", "aaaaaaaa", "thing", "Thing", null, null, Instant.EPOCH, null));

        final ManagedPlugin written = plugins.all().getFirst();
        assertNull(written.iconUrl());
        assertNull(written.pageUrl());
        assertNull(written.addedBy());
    }

    @Test
    void aDirectoryThatKnowsNothingAnswersAnEmptyListAndRefusesToPretendItWrote() {
        assertEquals(List.of(), PluginDirectory.NONE.all());
        assertEquals(List.of(), PluginDirectory.NONE.on("smp"));
        // A directory that cannot write must not report success.
        assertThrows(
                UnsupportedOperationException.class,
                () -> PluginDirectory.NONE.add(row("smp", "worldedit", "worldedit-bukkit")));
        assertThrows(UnsupportedOperationException.class, () -> PluginDirectory.NONE.remove("smp", "worldedit"));
    }

    private static void execute(final String sql) {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (final SQLException failure) {
            throw new IllegalStateException(sql, failure);
        }
    }
}
