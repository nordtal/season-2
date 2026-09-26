package eu.nordtal.s2.common.online;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import eu.nordtal.s2.common.access.AccessSchema;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Exercises {@link OnlineDirectory} against a real PostgreSQL running the real migration.
 *
 * Testcontainers driven by hand from {@link BeforeAll}, like every other integration test in this
 * module - the {@code junit-jupiter} extension is built against JUnit 5 and this repo is on the
 * JUnit 6 BOM - and this class skips itself when no Docker daemon is reachable.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OnlineDirectoryIntegrationTest {

    private static PostgreSQLContainer<?> postgres;
    private static PGSimpleDataSource dataSource;

    private OnlineDirectory online;

    @BeforeAll
    static void startDatabase() {
        assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "No Docker daemon reachable - skipping the PostgreSQL-backed online-count tests");

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
        execute("TRUNCATE TABLE online_count");
        online = OnlineDirectory.using(dataSource);
    }

    @Test
    void aSubjectWithNoWriteAtAllIsSimplyAbsentNotZero() {
        assertTrue(online.current().isEmpty());
    }

    @Test
    void aWrittenSubjectComesBackWithItsCount() {
        online.write(Map.of("smp", 3));

        final Map<String, OnlineCount> current = online.current();
        assertEquals(1, current.size());
        assertEquals(3, current.get("smp").players());
        assertEquals("smp", current.get("smp").subject());
    }

    @Test
    void aGenuineZeroIsWrittenAndReadBackAsZeroNotDropped() {
        online.write(Map.of("limbo", 0));

        assertTrue(online.current().containsKey("limbo"), "a 0 must not read like an unwritten row");
        assertEquals(0, online.current().get("limbo").players());
    }

    @Test
    void writingFourSubjectsAtOnceProducesFourRowsNotABatchFailure() {
        online.write(Map.of("smp", 5, "hunger-games", 2, "limbo", 0, "proxy", 7));

        final Map<String, OnlineCount> current = online.current();
        assertEquals(4, current.size());
        assertEquals(5, current.get("smp").players());
        assertEquals(2, current.get("hunger-games").players());
        assertEquals(0, current.get("limbo").players());
        assertEquals(7, current.get("proxy").players());
    }

    @Test
    void anEmptyWriteDoesNothing() {
        online.write(Map.of());
        assertTrue(online.current().isEmpty());
    }

    @Test
    void aNegativeCountIsRefusedBeforeItReachesTheTable() {
        assertThrows(IllegalArgumentException.class, () -> online.write(Map.of("smp", -1)));
        assertTrue(online.current().isEmpty(), "the refused write must not have left a partial row");
    }

    @Test
    void theSecondWriteReplacesTheRowThisIsTheWholePointOfTheTable() {
        online.write(Map.of("smp", 3));
        online.write(Map.of("smp", 9));

        assertEquals(
                1,
                count("SELECT count(*) FROM online_count WHERE subject = 'smp'"),
                "still one row, never a second one for the same subject");
        assertEquals(9, online.current().get("smp").players(), "and the newest value wins");
    }

    @Test
    void writingOneSubjectLeavesEveryOtherSubjectsRowUntouched() {
        online.write(Map.of("smp", 3, "limbo", 1));
        online.write(Map.of("smp", 4));

        assertEquals(4, online.current().get("smp").players());
        assertEquals(
                1,
                online.current().get("limbo").players(),
                "limbo was not in the second write, so its row must still be exactly what it was");
    }

    @Test
    void updatedMovesForwardOnAReplacingWrite() throws InterruptedException {
        online.write(Map.of("smp", 1));
        final Instant first = online.current().get("smp").updated();

        Thread.sleep(5);
        online.write(Map.of("smp", 1));
        final Instant second = online.current().get("smp").updated();

        assertFalse(second.isBefore(first), "a replacing write must not leave a stale timestamp behind");
    }

    private long count(final String sql) {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getLong(1);
        } catch (final SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void execute(final String sql) {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (final SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
