package eu.nordtal.s2.common.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import eu.nordtal.s2.common.access.AccessSchema;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Exercises {@link PluginDirectory} against a real PostgreSQL running the real migrations
 * (season-2-ops/129).
 *
 * <p>Nothing here has an in-memory stand-in, for the reason {@code UpdateDirectoryIntegrationTest}
 * gives: the two behaviours worth holding are the composite primary key and the
 * {@code ON CONFLICT DO UPDATE} on top of it, and both are PostgreSQL's and not Java's. The one
 * that matters most is that a second press of Install <b>refreshes</b> the row instead of failing -
 * because the field it refreshes, {@code file_prefix}, is what makes the removal able to find the
 * jar.</p>
 *
 * <p>Testcontainers is driven by hand from {@link BeforeAll}, like every other integration test in
 * this module, and these tests <b>skip themselves</b> when no Docker daemon is reachable.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PluginDirectoryIntegrationTest {

    private static PostgreSQLContainer<?> postgres;
    private static PGSimpleDataSource dataSource;

    private PluginDirectory plugins;

    @BeforeAll
    static void startDatabase() {
        assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "No Docker daemon reachable - skipping the PostgreSQL-backed plugin tests");

        postgres = new PostgreSQLContainer<>("postgres:17-alpine")
                .withDatabaseName("access")
                .withUsername("access")
                .withPassword("access");
        postgres.start();

        dataSource = new PGSimpleDataSource();
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());

        AccessSchema.migrate(dataSource);
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) {
            postgres.stop();
            postgres = null;
        }
        dataSource = null;
    }

    @BeforeEach
    void freshTable() {
        execute("TRUNCATE TABLE service_plugin");
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
    @DisplayName("a row comes back as it was written, with the database's own clock on it")
    void writesAndReadsOneRow() {
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
        // `added` is the column's default and not the value handed in: the record carries an
        // Instant because it has to come back somehow, and the row is timed by the database so
        // that two processes with two clocks cannot disagree about when a plugin appeared.
        assertTrue(written.added().isAfter(Instant.EPOCH), "added came from the caller, not from now()");
    }

    @Test
    @DisplayName("installing twice refreshes the row rather than failing")
    void addingAgainRefreshes() {
        plugins.add(row("smp", "worldedit", "worldedit-bukkit"));
        // What changes in practice is the prefix: the project renamed its artefact, somebody
        // pressed Install again, and the removal has to be able to find the NEW jar afterwards.
        plugins.add(row("smp", "worldedit", "worldedit-paper"));

        assertEquals(1, plugins.all().size(), "the second press wrote a second row");
        assertEquals("worldedit-paper", plugins.all().getFirst().filePrefix());
    }

    @Test
    @DisplayName("the same plugin on two services is two rows, and each service sees only its own")
    void oneRowPerServiceAndPlugin() {
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
    @DisplayName("removing takes one row and removing it twice is not an error")
    void removingIsIdempotent() {
        plugins.add(row("smp", "worldedit", "worldedit-bukkit"));

        plugins.remove("smp", "worldedit");
        plugins.remove("smp", "worldedit");

        assertEquals(0, plugins.all().size());
    }

    @Test
    @DisplayName("a service name the rest of the stack could not use is refused by the CHECK")
    void theServiceNameIsConstrained() {
        // The same alphabet `update_request.scope` and `service_hold.service` are held to. A row
        // naming `SMP ` or `../smp` is one the resolver would silently never match to a service,
        // which is the failure that leaves a plugin not installed forever with nothing saying why.
        assertThrows(RuntimeException.class, () -> plugins.add(row("SMP", "worldedit", "worldedit-bukkit")));
    }

    @Test
    @DisplayName("an icon and a page are allowed to be absent - not every project has a picture")
    void nullableColumnsAreNullable() {
        plugins.add(new ManagedPlugin("limbo", "thing", "aaaaaaaa", "thing", "Thing", null, null, Instant.EPOCH, null));

        final ManagedPlugin written = plugins.all().getFirst();
        assertNull(written.iconUrl());
        assertNull(written.pageUrl());
        assertNull(written.addedBy());
    }

    @Test
    @DisplayName("a directory that knows nothing answers an empty list and refuses to pretend it wrote")
    void theEmptyDirectoryIsTheSafeDirection() {
        assertEquals(List.of(), PluginDirectory.NONE.all());
        assertEquals(List.of(), PluginDirectory.NONE.on("smp"));
        // Reading is harmless and writing is not: a directory that cannot write and says it did is
        // a button that reports success and installs nothing, forever.
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
