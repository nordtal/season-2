package eu.nordtal.season.stewardagent;

import static eu.nordtal.season.database.AdminTexts.TEXTS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.database.DatabaseText;
import eu.nordtal.season.database.TestDatabase;
import eu.nordtal.season.database.audit.AuditDirectory;
import eu.nordtal.season.database.audit.AuditEntry;
import eu.nordtal.season.database.audit.JournalAction;
import eu.nordtal.season.database.inbox.StewardRequest;
import eu.nordtal.season.database.update.UpdateDirectory;
import eu.nordtal.season.database.update.UpdateKind;
import eu.nordtal.season.database.update.UpdateRequest;
import eu.nordtal.season.stewardagent.plugin.ManagedPlugin;
import eu.nordtal.season.stewardagent.plugin.PluginDirectory;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
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
 * Tests the host's {@code request remove-plugin SERVICE PLUGIN} against a real PostgreSQL running the migrations.
 *
 * It writes the run and the journal entry Steward's "Remove" writes, as the host, and refuses what nobody added.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HostRemovalIntegrationTest {

    private static DataSource dataSource;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @BeforeAll
    static void startDatabase() {
        dataSource = TestDatabase.fresh().dataSource();
    }

    @BeforeEach
    void freshTables() {
        out.reset();
        err.reset();
        execute("TRUNCATE TABLE service_plugin, plugin_file, steward_inbox, journal_entry CASCADE");
        final PluginDirectory plugins = PluginDirectory.using(dataSource);
        plugins.add(added("smp", "chunky"));
        plugins.add(added("smp", "worldedit"));
        plugins.add(added("hunger-games", "spark"));
    }

    @Test
    void anAddedPluginGetsTheRunAndTheJournalEntryOfSteward_sRemoveAsTheHost() {
        final int exit = remove("smp", "chunky");

        assertEquals(0, exit, err());
        final long id = Long.parseLong(out().strip());
        final UpdateDirectory runs = UpdateDirectory.using(dataSource);
        final UpdateRequest written = runs.find(id).orElseThrow();
        assertEquals(UpdateKind.REMOVE_PLUGIN, written.kind());
        assertEquals(
                new StewardRequest.RemovePlugin("chunky"), runs.requestOf(id).orElseThrow());
        assertEquals(List.of("smp"), runs.scopeOf(id));
        final List<AuditEntry> journal = AuditDirectory.using(dataSource).recent(10);
        assertEquals(1, journal.size());
        assertEquals(JournalAction.REMOVE_PLUGIN, journal.getFirst().action());
        assertEquals(Actor.HOST, journal.getFirst().actor());
        assertEquals(
                DatabaseText.english(TEXTS.journal().removePlugin(id, "smp", "chunky")),
                DatabaseText.english(journal.getFirst().line()));
    }

    @Test
    void aPluginNobodyAddedToTheServiceIsRefusedWithTheAddedOnes() {
        final int exit = remove("smp", "spark");

        assertEquals(2, exit);
        assertNothingWritten();
        assertTrue(err().contains("spark"), err());
        assertTrue(err().contains("smp: chunky, worldedit"), err());
        assertTrue(err().contains("hunger-games: spark"), err());
    }

    @Test
    void anUnknownServiceIsRefusedWithTheAddedPlugins() {
        final int exit = remove("smpp", "chunky");

        assertEquals(2, exit);
        assertNothingWritten();
        assertTrue(err().contains("smpp"), err());
        assertTrue(err().contains("smp: chunky, worldedit"), err());
    }

    @Test
    void aMissingPluginNameIsRefused() {
        assertEquals(
                2,
                HostRequests.removePlugin(
                        UpdateDirectory.using(dataSource),
                        PluginDirectory.using(dataSource),
                        AuditDirectory.using(dataSource),
                        List.of("request", "remove-plugin", "smp"),
                        print(out),
                        print(err)));
        assertNothingWritten();
    }

    private int remove(final String service, final String plugin) {
        return HostRequests.removePlugin(
                UpdateDirectory.using(dataSource),
                PluginDirectory.using(dataSource),
                AuditDirectory.using(dataSource),
                List.of("request", "remove-plugin", service, plugin),
                print(out),
                print(err));
    }

    private void assertNothingWritten() {
        assertEquals(List.of(), AuditDirectory.using(dataSource).recent(10));
        assertEquals("", out());
    }

    private static ManagedPlugin added(final String service, final String slug) {
        return new ManagedPlugin(
                service,
                slug,
                "id-" + slug,
                slug,
                slug,
                null,
                null,
                Instant.EPOCH,
                Actor.person(DiscordId.of("300000000000000077")));
    }

    private static PrintStream print(final ByteArrayOutputStream to) {
        return new PrintStream(to, true, StandardCharsets.UTF_8);
    }

    private String out() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String err() {
        return err.toString(StandardCharsets.UTF_8);
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
