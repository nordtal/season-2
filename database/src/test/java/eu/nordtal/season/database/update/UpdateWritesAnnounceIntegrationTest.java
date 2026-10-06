package eu.nordtal.season.database.update;

import static eu.nordtal.season.database.AdminTexts.TEXTS;
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
import org.junit.jupiter.api.Test;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;

/**
 * Every write the proxy reads the run row or the holds for says so on the channel.
 *
 * The proxy reads them only when told, so a write that stays silent is read late.
 */
class UpdateWritesAnnounceIntegrationTest {

    private static DataSource dataSource;

    @BeforeAll
    static void startDatabase() {
        dataSource = TestDatabase.fresh().dataSource();
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

            updates.putBack(carried, TEXTS.report().words("restored from a dump taken while this ran"));
            assertTrue(heard(pg, 5000), "a row put back after a restore was written without a word");
        }
    }

    private static boolean heard(final PGConnection pg, final int millis) throws SQLException {
        final PGNotification[] received = pg.getNotifications(millis);
        return received != null && received.length > 0;
    }
}
