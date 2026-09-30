package eu.nordtal.s2.database.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.database.TestDatabase;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * A run handed from one steward-worker to the next, against a real PostgreSQL.
 *
 * The row is the whole handover, so its claim, countdown and orphan rules are checked in SQL, not on a fake.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UpdateHandoverIntegrationTest {
    private static DataSource dataSource;

    private UpdateDirectory updates;

    @BeforeAll
    static void startDatabase() {
        dataSource = TestDatabase.fresh().dataSource();
    }

    @BeforeEach
    void freshInbox() {
        execute("TRUNCATE TABLE service_hold, update_request RESTART IDENTITY");
        updates = UpdateDirectory.using(dataSource);
    }

    @Test
    void aHandedOverRunIsClaimedAgainWithItsReportAndStaysOpenInBetween() {
        // The new worker must find the run and its note.
        final UpdateRequest run = updates.submit(UpdateKind.UPDATE, UpdateSource.CONSOLE, "a", Duration.ZERO);
        assertTrue(updates.claimNext().isPresent());

        assertTrue(updates.handOver(run.id(), "{\"stage\":\"RESOLVING\"}"));

        final UpdateRequest waiting = updates.find(run.id()).orElseThrow();
        assertEquals(UpdateStatus.PENDING, waiting.status());
        assertNull(waiting.started(), "nobody is running it until the next worker claims it");
        assertEquals(run.id(), updates.open().orElseThrow().id(), "and no second run can slip in meanwhile");
        assertTrue(updates.countingDown().isEmpty(), "and nobody is counted down to a handover");
        assertEquals(0, updates.settleOrphans("orphaned"), "a restart must not settle a run that was handed over");

        final UpdateRequest again = updates.claimNext().orElseThrow();
        assertEquals(run.id(), again.id());
        assertEquals("{\"stage\":\"RESOLVING\"}", again.result());
    }

    @Test
    void onlyARunningRunCanBeHandedOver() {
        // A run settled meanwhile, say by settleOrphans, must not return to PENDING.
        final UpdateRequest run = updates.submit(UpdateKind.UPDATE, UpdateSource.CONSOLE, "a", Duration.ZERO);
        assertTrue(updates.claimNext().isPresent());
        updates.finish(run.id(), UpdateStatus.FAILED, "{}");

        assertFalse(updates.handOver(run.id(), "{}"));
        assertEquals(UpdateStatus.FAILED, updates.find(run.id()).orElseThrow().status());
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
