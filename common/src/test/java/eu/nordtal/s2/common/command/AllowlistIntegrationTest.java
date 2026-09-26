package eu.nordtal.s2.common.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import eu.nordtal.s2.common.access.AccessSchema;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Publishing the command allowlist, against a real PostgreSQL running the real migrations.
 *
 * <b>Why this cannot be an in-memory test</b>
 *
 * Every claim worth making here is the database's. The upsert only writes when the value actually
 * moved, which is what keeps a proxy restart from waking three servers about a list nobody edited -
 * and that is expressed as {@code WHERE network_setting.value IS DISTINCT FROM EXCLUDED.value} on the
 * conflict branch, which no fake can evaluate. The notification is a bare {@code NOTIFY}, the one in
 * this repository issued as its own statement rather than riding inside a write; whether pgjdbc will
 * even run that through {@code executeUpdate} is a fact about the driver.
 *
 * It also pins the shape of what a backend reads back, because the row is text: a list published
 * on one version of the software is read by another, and the only thing keeping the two in step is
 * that {@code serialise} and {@code deserialise} are inverses through an actual column.
 *
 * Skips itself when no Docker daemon is reachable, like every other integration test here.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AllowlistIntegrationTest {

    private static PostgreSQLContainer<?> postgres;
    private static PGSimpleDataSource dataSource;

    private AllowlistDirectory directory;

    @BeforeAll
    static void startDatabase() {
        assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "No Docker daemon reachable - skipping the command allowlist tests");

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
    void clean() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM network_setting WHERE key = 'network.command-allowlist'");
        }
        directory = AllowlistDirectory.using(dataSource);
    }

    @Test
    void nothingPublishedReadsAsEmptyNotAsAnEmptyList() {
        // No list published means filter nothing; an empty list would refuse every command.
        assertEquals(Optional.empty(), directory.published());
    }

    @Test
    void aPublishedListComesBackAsTheSameList() {
        final CommandAllowlist list = CommandAllowlist.parse(List.of("/smp status", "msg", "hg ready", "discord"));

        assertTrue(directory.publish(list), "the first publish has to write something");
        assertEquals(Optional.of(list), directory.published());
    }

    @Test
    void publishingTheSameListAgainWritesNothingAndWakesNobody() {
        // A proxy restart must not make three servers re-read an unchanged list.
        final CommandAllowlist list = CommandAllowlist.parse(List.of("msg", "r"));
        assertTrue(directory.publish(list));
        assertEquals(
                false, directory.publish(list), "the conflict branch's IS DISTINCT FROM guard is not doing anything");
        assertEquals(Optional.of(list), directory.published());
    }

    @Test
    void anEditedListReplacesTheOldOneRatherThanSittingNextToIt() {
        directory.publish(CommandAllowlist.parse(List.of("msg")));
        final CommandAllowlist edited = CommandAllowlist.parse(List.of("msg", "rules"));

        assertTrue(directory.publish(edited));
        assertEquals(Optional.of(edited), directory.published());
    }

    @Test
    void anEmptiedListIsPublishedAsAnEmptyListAndReadBackAsOne() {
        directory.publish(CommandAllowlist.parse(List.of("msg")));

        assertTrue(directory.publish(CommandAllowlist.NOTHING));
        assertEquals(
                Optional.of(CommandAllowlist.NOTHING),
                directory.published(),
                "an operator who empties the list on purpose must not read as a proxy that has"
                        + " never run - those two are told apart by the presence of the row");
    }
}
