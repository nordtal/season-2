package eu.nordtal.s2.smp.db;

import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.database.Jdbis;
import eu.nordtal.s2.database.TestDatabase;
import eu.nordtal.s2.database.notify.Channel;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;

/** Every write the boards and the HUD draw signals on {@link Channel#SMP}, so the surfaces need no poll. */
class SurfaceSignalIntegrationTest {

    private static DataSource dataSource;

    @BeforeAll
    static void startDatabase() {
        dataSource = TestDatabase.fresh().dataSource();
    }

    @Test
    void progressMilestonesAndAuraAllSignal() throws Exception {
        final SmpDao dao = Jdbis.over(dataSource).onDemand(SmpDao.class);
        final DiscordId player = DiscordId.of("123456789012345678");
        execute("INSERT INTO smp_milestone (key) VALUES ('first'), ('second')");
        execute("INSERT INTO discord_user (discord_id) VALUES ('123456789012345678')");
        dao.ensureObjective("first", "wood", "HAND_IN", 10);
        final UUID objective = dao.objectivesOf("first").getFirst().id();

        try (Connection listener = dataSource.getConnection()) {
            try (Statement statement = listener.createStatement()) {
                statement.execute("LISTEN " + Channel.SMP.sqlName());
            }
            final PGConnection pg = listener.unwrap(PGConnection.class);

            dao.activateMilestone("first");
            assertTrue(signalled(pg), "activating a milestone");
            dao.addObjectiveProgress(objective, 3);
            assertTrue(signalled(pg), "progress on an objective");
            dao.countOnce(objective, player);
            assertTrue(signalled(pg), "a gate counting a player");
            dao.completeObjective(objective);
            assertTrue(signalled(pg), "a finished objective");
            dao.completeMilestone("first");
            assertTrue(signalled(pg), "a finished milestone");
            dao.activateAfter("second", 1);
            assertTrue(signalled(pg), "the next milestone");
            dao.addAura(player, 5, "test", null);
            assertTrue(signalled(pg), "an aura change");
        }
    }

    private static boolean signalled(final PGConnection pg) throws SQLException {
        final PGNotification[] received = pg.getNotifications(5000);
        return received != null && received.length > 0;
    }

    private static void execute(final String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
