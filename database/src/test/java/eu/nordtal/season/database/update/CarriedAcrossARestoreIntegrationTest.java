package eu.nordtal.season.database.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.database.TestDatabase;
import eu.nordtal.season.database.inbox.StewardRequest;
import java.time.Duration;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A database restore replaces the inbox with the one in the dump, and the run that does it survives. */
class CarriedAcrossARestoreIntegrationTest {

    private DataSource dataSource;
    private UpdateDirectory updates;

    @BeforeEach
    void open() {
        dataSource = TestDatabase.fresh().dataSource();
        updates = UpdateDirectory.using(dataSource);
    }

    @Test
    void aRunCarriedAcrossARestoreIsItsOwnRowAgainAndWhatTheDumpHeldOpenIsFailed() throws Exception {
        final UpdateRequest backup = updates.submit(UpdateKind.BACKUP, Actor.HOST, Duration.ZERO);
        assertTrue(updates.claimNext().isPresent());
        assertTrue(updates.finish(backup.id(), RunStage.DONE).isPresent());
        final UpdateRequest restore = updates.submit(
                new StewardRequest.Restore("nordtal-20261001T000000Z.dump"), List.of(), Actor.HOST, Duration.ZERO);
        assertTrue(updates.claimNext().isPresent());
        final String carried = updates.carry(restore.id()).orElseThrow();

        // What the dump holds: the backup that was running when it was taken and no sign of this run.
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement()) {
            statement.execute("DELETE FROM steward_inbox WHERE id = " + restore.id());
            statement.execute("UPDATE steward_inbox SET status = 'RUNNING', finished = NULL, stage = 'BACKING_UP'"
                    + " WHERE id = " + backup.id());
            statement.execute("INSERT INTO run_step (run, seq, step, service) VALUES (" + backup.id()
                    + ", 1, 'SAVE_VOLUME', 'nordtal-s2_mc-smp')");
        }
        updates.putBack(carried);

        final UpdateRequest back = updates.find(restore.id()).orElseThrow();
        assertEquals(UpdateStatus.RUNNING, back.status());
        assertEquals(
                new StewardRequest.Restore("nordtal-20261001T000000Z.dump"),
                updates.requestOf(restore.id()).orElseThrow());
        final UpdateRequest settled = updates.find(backup.id()).orElseThrow();
        assertEquals(UpdateStatus.FAILED, settled.status(), "an open row from the dump would be claimed again");
        assertEquals(RunStage.FAILED, settled.stage());
        final RunStep.Row open = updates.recordOf(backup.id()).steps().getFirst();
        assertEquals(RunStep.Outcome.FAILED, open.outcome());
        assertEquals(RunStep.Reason.INTERRUPTED, open.reason(), "nothing ends the step the dump held open");

        assertTrue(updates.finish(restore.id(), RunStage.DONE).isPresent());
        assertTrue(
                updates.submit(UpdateKind.START, Actor.HOST, Duration.ZERO).id() > restore.id(),
                "the sequence counts on from the row put back, not from the dump");
    }
}
