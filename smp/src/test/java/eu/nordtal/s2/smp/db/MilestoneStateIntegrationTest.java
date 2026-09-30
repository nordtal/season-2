package eu.nordtal.s2.smp.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.database.Jdbis;
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

/** The milestone state machine against the real {@code smp_milestone_state_check}. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MilestoneStateIntegrationTest {
    private static DataSource dataSource;

    private SmpDao dao;

    @BeforeAll
    static void startDatabase() {
        dataSource = TestDatabase.fresh().dataSource();
    }

    @BeforeEach
    void freshTrack() {
        execute("TRUNCATE TABLE smp_milestone CASCADE");
        execute("INSERT INTO smp_milestone (key) VALUES ('waiting'), ('departure')");
        dao = Jdbis.over(dataSource).onDemand(SmpDao.class);
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
    void anOutOfOrderUnlockLeavesExactlyOneActiveMilestone() {
        dao.activateMilestone("waiting");

        assertEquals(Optional.empty(), dao.completeMilestone("departure"), "a locked milestone was unlocked");
        assertEquals(List.of(), dao.completedMilestoneKeys());
        assertEquals(List.of("waiting"), activeKeys());
    }

    /** The track was read with one milestone done; a reset in between must not let a later one become active. */
    @Test
    void anActivationReadBeforeAResetDoesNothing() {
        dao.activateMilestone("waiting");
        dao.completeMilestone("waiting");
        execute("UPDATE smp_milestone SET state = 'LOCKED', unlocked = NULL");

        assertEquals(0, dao.activateAfter("departure", 1), "the count read before the reset no longer holds");
        assertEquals(List.of(), activeKeys());
        assertEquals(1, dao.activateAfter("waiting", 0));
        assertEquals(0, dao.activateAfter("departure", 0), "one active milestone at a time");
        assertEquals(List.of("waiting"), activeKeys());
    }

    @Test
    void objectiveRowsAreEnsured() {
        // Progress needs the smp_objective row; this is the insert that creates it.
        dao.ensureObjective("departure", "logs", "HAND_IN", 64);
        dao.ensureObjective("departure", "logs", "HAND_IN", 64);
        assertEquals(1, dao.objectivesOf("departure").size(), "one row, however often the file is read");
        assertEquals(64, dao.objectivesOf("departure").getFirst().target());

        dao.ensureObjective("departure", "logs", "HAND_IN", 32);
        assertEquals(32, dao.objectivesOf("departure").getFirst().target(), "a lowered target reaches the row");
        assertEquals(0, dao.objectivesOf("departure").getFirst().amount(), "and the progress is untouched");
    }

    private static List<String> activeKeys() {
        return Jdbis.over(dataSource)
                .withHandle(handle -> handle.createQuery(
                                "SELECT key FROM smp_milestone WHERE state = 'ACTIVE' ORDER BY key")
                        .mapTo(String.class)
                        .list());
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
