package eu.nordtal.season.smp;

import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.database.Jdbis;
import eu.nordtal.season.database.TestDatabase;
import eu.nordtal.season.database.notify.Channel;
import eu.nordtal.season.smp.aura.AuraDao;
import eu.nordtal.season.smp.milestone.TrackDao;
import eu.nordtal.season.smp.progress.ProgressDao;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import javax.sql.DataSource;
import org.jdbi.v3.core.Jdbi;
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
        final Jdbi jdbi = Jdbis.over(dataSource);
        final TrackDao track = jdbi.onDemand(TrackDao.class);
        final ProgressDao progress = jdbi.onDemand(ProgressDao.class);
        final AuraDao aura = jdbi.onDemand(AuraDao.class);
        final DiscordId player = DiscordId.of("123456789012345678");
        execute("INSERT INTO smp_milestone (key) VALUES ('first'), ('second')");
        execute("INSERT INTO discord_user (discord_id) VALUES ('123456789012345678')");
        track.ensureObjective("first", "wood", "HAND_IN", 10);
        final UUID objective = track.objectivesOf("first").getFirst().id();

        try (Connection listener = dataSource.getConnection()) {
            try (Statement statement = listener.createStatement()) {
                statement.execute("LISTEN " + Channel.SMP.sqlName());
            }
            final PGConnection pg = listener.unwrap(PGConnection.class);

            track.activateMilestone("first");
            assertTrue(signalled(pg), "activating a milestone");
            progress.addObjectiveProgress(objective, 3);
            assertTrue(signalled(pg), "progress on an objective");
            progress.countOnce(objective, player);
            assertTrue(signalled(pg), "a gate counting a player");
            progress.completeObjective(objective);
            assertTrue(signalled(pg), "a finished objective");
            track.completeMilestone("first");
            assertTrue(signalled(pg), "a finished milestone");
            track.activateAfter("second", 1);
            assertTrue(signalled(pg), "the next milestone");
            aura.addAura(player, 5, "test", null);
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
