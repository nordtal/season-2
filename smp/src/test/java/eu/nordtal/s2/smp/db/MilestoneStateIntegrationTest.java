package eu.nordtal.s2.smp.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import org.flywaydb.core.Flyway;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * The milestone state machine against the real {@code smp_milestone_state_check}.
 *
 * {@code SmpDao#completeMilestone} used to write {@code 'COMPLETE'} - a value V6's CHECK refuses, so every
 * unlock threw - and {@code completedMilestoneKeys()} read the same value back, so it never returned a row. Escape
 * hatch 2 ( {@code /smp milestone unlock}) had never worked, the automatic unlock at the end of an objective set
 * would have failed the same way, and the season's list of unlocked milestones was empty by construction. Found by
 * typing the command on the local stack; nothing in memory could have found it, because the value only meets the
 * constraint in a database.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MilestoneStateIntegrationTest {

    private static PostgreSQLContainer<?> postgres;
    private static PGSimpleDataSource dataSource;

    private SmpDao dao;

    @BeforeAll
    static void startDatabase() {
        assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "No Docker daemon reachable - skipping the PostgreSQL-backed milestone state tests");
        postgres = new PostgreSQLContainer<>("postgres:17-alpine")
                .withDatabaseName("access")
                .withUsername("access")
                .withPassword("access");
        postgres.start();

        dataSource = new PGSimpleDataSource();
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());

        Flyway.configure(MilestoneStateIntegrationTest.class.getClassLoader())
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
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
    void freshTrack() {
        execute("TRUNCATE TABLE smp_milestone CASCADE");
        execute("INSERT INTO smp_milestone (key) VALUES ('waiting'), ('departure')");
        dao = Jdbi.create(dataSource)
                .installPlugin(new SqlObjectPlugin())
                .installPlugin(new PostgresPlugin())
                .onDemand(SmpDao.class);
    }

    @Test
    void theWholeLadderMeetsTheConstraint() {
        assertEquals(1, dao.activateMilestone("waiting"));
        assertEquals(Optional.of("waiting"), dao.activeMilestoneKey());

        final Optional<String> unlocked = dao.completeMilestone("waiting");
        assertEquals(
                Optional.of("waiting"),
                unlocked,
                "the UPDATE has to return the key - the row it wrote is what the announcement is for");
        assertEquals(
                List.of("waiting"),
                dao.completedMilestoneKeys(),
                "and the read side has to see the same value the write side used");
        assertEquals(Optional.empty(), dao.activeMilestoneKey(), "unlocked is not active any more");
    }

    @Test
    void aSecondCompletionIsEmpty() {
        dao.activateMilestone("waiting");
        assertTrue(dao.completeMilestone("waiting").isPresent());
        assertEquals(
                Optional.empty(),
                dao.completeMilestone("waiting"),
                "the engine and the escape hatch may both call this; only one may announce");
        assertEquals(List.of("waiting"), dao.completedMilestoneKeys());
    }

    @Test
    void escapeHatchTwoSkipsActive() {
        // /smp milestone unlock on a milestone the season has not reached: the blunt escape hatch.
        assertEquals(Optional.of("departure"), dao.completeMilestone("departure"));
        assertEquals(List.of("departure"), dao.completedMilestoneKeys());
    }

    @Test
    void objectiveRowsAreEnsured() {
        // smp_objective was once never inserted into, so progress ran against an empty table; this is that insert.
        dao.ensureObjective("departure", "logs", "HAND_IN", 64);
        dao.ensureObjective("departure", "logs", "HAND_IN", 64);
        assertEquals(1, dao.objectivesOf("departure").size(), "one row, however often the file is read");
        assertEquals(64, dao.objectivesOf("departure").getFirst().target());

        dao.ensureObjective("departure", "logs", "HAND_IN", 32);
        assertEquals(32, dao.objectivesOf("departure").getFirst().target(), "a lowered target reaches the row");
        assertEquals(0, dao.objectivesOf("departure").getFirst().amount(), "and the progress is untouched");
    }

    private static void execute(final String sql) {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (final SQLException exception) {
            throw new IllegalStateException(sql, exception);
        }
    }
}
