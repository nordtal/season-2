package eu.nordtal.season.database.update;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.database.TestDatabase;
import eu.nordtal.season.database.notify.Channel;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;

/** Every write that the proxy reads the run row or the holds for is announced on the channel. */
class UpdateWritesAnnounceIntegrationTest {

    private static DataSource dataSource;

    @BeforeAll
    static void startDatabase() {
        dataSource = TestDatabase.fresh().dataSource();
    }

    /** Each test leaves its run open, and an open run refuses the next. */
    @BeforeEach
    void emptyTheQueue() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE TABLE service_hold, steward_inbox RESTART IDENTITY CASCADE");
        }
    }

    @Test
    void holdingReleasingAndPuttingARowBackAnnounceThemselves() throws Exception {
        final UpdateDirectory updates = UpdateDirectory.using(dataSource);
        final UpdateRequest down = updates.submit(UpdateKind.DOWN, Actor.HOST, Duration.ZERO, List.of("smp"));
        assertTrue(updates.claimNext().isPresent());
        final String carried = updates.carry(down.id()).orElseThrow();

        try (Connection listener = dataSource.getConnection()) {
            try (Statement statement = listener.createStatement()) {
                statement.execute("LISTEN " + Channel.UPDATE.sqlName());
            }
            final PGConnection pg = listener.unwrap(PGConnection.class);

            updates.hold("smp", Actor.HOST, down.id());
            assertTrue(heard(pg, 5000), "a hold was written without a word");

            updates.release("smp");
            assertTrue(heard(pg, 5000), "a hold was released without a word");

            updates.release("smp");
            assertFalse(heard(pg, 500), "releasing nothing announced a change that never happened");

            updates.putBack(carried);
            assertTrue(heard(pg, 5000), "a row put back after a restore was written without a word");
        }
    }

    @Test
    void anEndedVerifyIsAnnouncedAndAnotherEndedStepIsNot() throws Exception {
        final UpdateDirectory updates = UpdateDirectory.using(dataSource);
        final long run =
                updates.submit(UpdateKind.RESTART, Actor.HOST, Duration.ZERO).id();
        final RunRecorder recorder = updates.recorder();
        final long stop = recorder.begin(run, RunStep.Kind.STOP, "limbo");
        final long verify = recorder.begin(run, RunStep.Kind.VERIFY, "limbo");

        try (Connection listener = dataSource.getConnection()) {
            try (Statement statement = listener.createStatement()) {
                statement.execute("LISTEN " + Channel.UPDATE.sqlName());
            }
            final PGConnection pg = listener.unwrap(PGConnection.class);

            recorder.end(run, stop, RunStep.Outcome.DONE, null, null);
            assertFalse(heard(pg, 500), "a stop woke every proxy although none routes on it");

            recorder.end(run, verify, RunStep.Outcome.DONE, null, null);
            assertTrue(heard(pg, 5000), "a passed health check was written without a word");

            recorder.end(run, verify, RunStep.Outcome.DONE, null, null);
            assertFalse(heard(pg, 500), "a step that had ended announced again");
        }
    }

    private static boolean heard(final PGConnection pg, final int millis) throws SQLException {
        final PGNotification[] received = pg.getNotifications(millis);
        return received != null && received.length > 0;
    }
}
