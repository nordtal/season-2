package eu.nordtal.s2.database.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.database.Actor;
import eu.nordtal.s2.database.TestDatabase;
import eu.nordtal.s2.database.inbox.StewardRequest;
import java.time.Duration;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A database restore replaces the inbox with the dump's; the run carrying it out is carried across. */
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
        assertTrue(updates.finish(backup.id(), UpdateStatus.DONE, "{}").isPresent());
        final UpdateRequest restore = updates.submit(
                new StewardRequest.Restore(List.of(), "nordtal-20261001T000000Z.dump"), Actor.HOST, Duration.ZERO);
        assertTrue(updates.claimNext().isPresent());
        final String carried = updates.carry(restore.id()).orElseThrow();

        // What the dump holds: the backup that was running when it was taken, and no sign of this run.
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement()) {
            statement.execute("DELETE FROM steward_inbox WHERE id = " + restore.id());
            statement.execute("UPDATE steward_inbox SET status = 'RUNNING', finished = NULL WHERE id = " + backup.id());
        }
        updates.putBack(carried, "restored from a dump taken while this ran");

        final UpdateRequest back = updates.find(restore.id()).orElseThrow();
        assertEquals(UpdateStatus.RUNNING, back.status());
        assertEquals(
                new StewardRequest.Restore(List.of(), "nordtal-20261001T000000Z.dump"),
                updates.requestOf(restore.id()).orElseThrow());
        final UpdateRequest settled = updates.find(backup.id()).orElseThrow();
        assertEquals(UpdateStatus.FAILED, settled.status(), "an open row from the dump would be claimed again");
        assertEquals("restored from a dump taken while this ran", settled.result());

        assertTrue(updates.finish(restore.id(), UpdateStatus.DONE, "{}").isPresent());
        assertTrue(
                updates.submit(UpdateKind.START, Actor.HOST, Duration.ZERO).id() > restore.id(),
                "the sequence counts on from the row put back, not from the dump");
    }
}
