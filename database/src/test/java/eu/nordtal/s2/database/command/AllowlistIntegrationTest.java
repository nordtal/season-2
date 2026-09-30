package eu.nordtal.s2.database.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.database.TestDatabase;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Publishes the command allowlist against a real PostgreSQL running the real migrations.
 *
 * The change-only upsert and the bare {@code NOTIFY} are the database's; the tests skip themselves without Docker.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AllowlistIntegrationTest {
    private static DataSource dataSource;

    private AllowlistDirectory directory;

    @BeforeAll
    static void startDatabase() {
        dataSource = TestDatabase.fresh().dataSource();
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
