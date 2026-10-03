package eu.nordtal.s2.smp.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import eu.nordtal.s2.database.Jdbis;
import eu.nordtal.s2.database.TestDatabase;
import eu.nordtal.s2.database.access.AccessReader;
import eu.nordtal.s2.database.inbox.ServerRefusal;
import eu.nordtal.s2.papercommon.command.Answer;
import eu.nordtal.s2.papercommon.player.Identities;
import eu.nordtal.s2.smp.db.ObjectiveRow;
import eu.nordtal.s2.smp.db.SmpDao;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The track actions an admin takes on the console or through Steward, refused against the track as it stands. */
class SmpAdminIntegrationTest {

    private static DataSource dataSource;

    /** What reached the running track, as {@code objective <milestone>/<key>} or {@code milestone <key>}. */
    private final List<String> reached = new ArrayList<>();

    private SmpAdmin admin;

    @BeforeAll
    static void startDatabase() {
        dataSource = TestDatabase.fresh().dataSource();
    }

    @BeforeEach
    void freshTrack() {
        execute("TRUNCATE TABLE smp_milestone CASCADE");
        final SmpDao dao = Jdbis.over(dataSource).onDemand(SmpDao.class);
        final Identities identities = new Identities(players -> List.of());
        admin = new SmpAdmin(
                dao,
                new SmpAdmin.Track() {
                    @Override
                    public void finishObjective(final String milestone, final ObjectiveRow objective) {
                        reached.add("objective " + milestone + "/" + objective.key());
                    }

                    @Override
                    public void unlockMilestone(final String milestone) {
                        reached.add("milestone " + milestone);
                    }
                },
                identities,
                AccessReader.using(dataSource, Clock.systemUTC()),
                Logger.getAnonymousLogger());
    }

    private static String refusal(final Answer answer) {
        return assertInstanceOf(Answer.Refused.class, answer).refusal().reason().name();
    }

    @Test
    void withNoActiveMilestoneNothingOfTheTrackCanBeClosed() {
        execute("INSERT INTO smp_milestone (key, state) VALUES ('waiting', 'LOCKED')");

        assertEquals(ServerRefusal.NO_ACTIVE_MILESTONE.name(), refusal(admin.completeObjective("iron")));
        assertEquals(ServerRefusal.NO_ACTIVE_MILESTONE.name(), refusal(admin.unlockMilestone("waiting")));
        assertEquals(List.of(), reached);
    }

    @Test
    void aClosedObjectiveIsNotOpenAnyMoreAndIsRefusedByItsKey() {
        execute("INSERT INTO smp_milestone (key, state) VALUES ('waiting', 'ACTIVE')");
        execute("INSERT INTO smp_objective (milestone_key, key, type, amount, target, completed) VALUES"
                + " ('waiting', 'coal', 'STATISTIC', 10, 10, now())");

        assertEquals(ServerRefusal.NO_SUCH_OBJECTIVE.name(), refusal(admin.completeObjective("coal")));
        assertEquals(ServerRefusal.NO_SUCH_OBJECTIVE.name(), refusal(admin.completeObjective("gold")));
        assertEquals(List.of(), reached);
    }

    @Test
    void anOpenObjectiveOfTheActiveMilestoneReachesTheTrack() {
        execute("INSERT INTO smp_milestone (key, state) VALUES ('waiting', 'ACTIVE')");
        execute("INSERT INTO smp_objective (milestone_key, key, type, amount, target, completed) VALUES"
                + " ('waiting', 'iron', 'HAND_IN', 64, 128, NULL)");

        assertInstanceOf(Answer.Done.class, admin.completeObjective("iron"));
        assertEquals(List.of("objective waiting/iron"), reached);
    }

    @Test
    void onlyTheActiveMilestoneCanBeUnlocked() {
        execute("INSERT INTO smp_milestone (key, state) VALUES ('waiting', 'ACTIVE'), ('departure', 'LOCKED')");

        final Answer outOfOrder = admin.unlockMilestone("departure");
        assertEquals(ServerRefusal.MILESTONE_NOT_ACTIVE.name(), refusal(outOfOrder));
        assertEquals(
                "waiting",
                ((Answer.Refused) outOfOrder).refusal().message().args().get("active"),
                "the refusal names the milestone that can be unlocked");

        assertInstanceOf(Answer.Done.class, admin.unlockMilestone("waiting"));
        assertEquals(List.of("milestone waiting"), reached);
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
