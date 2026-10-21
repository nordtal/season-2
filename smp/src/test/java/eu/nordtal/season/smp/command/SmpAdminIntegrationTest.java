package eu.nordtal.season.smp.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.database.Jdbis;
import eu.nordtal.season.database.TestDatabase;
import eu.nordtal.season.database.access.AccessReader;
import eu.nordtal.season.database.inbox.ServerRefusal;
import eu.nordtal.season.papercommon.command.Answer;
import eu.nordtal.season.smp.aura.AuraDao;
import eu.nordtal.season.smp.milestone.ObjectiveRow;
import eu.nordtal.season.smp.milestone.TrackDao;
import eu.nordtal.season.smp.wheel.SpinDao;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SmpAdminIntegrationTest {

    private static final DiscordId PERSON = DiscordId.of("111111111111111111");

    private static final DiscordId ADMIN = DiscordId.of("222222222222222222");

    private static DataSource dataSource;

    private final List<String> reached = new ArrayList<>();

    private SmpAdmin admin;

    private Jdbi jdbi;

    @BeforeAll
    static void startDatabase() {
        dataSource = TestDatabase.fresh().dataSource();
    }

    @BeforeEach
    void freshTrack() {
        execute("TRUNCATE TABLE smp_milestone, discord_user CASCADE");
        jdbi = Jdbis.over(dataSource);
        admin = new SmpAdmin(
                jdbi.onDemand(TrackDao.class),
                jdbi.onDemand(AuraDao.class),
                jdbi.onDemand(SpinDao.class),
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

    @Test
    void aSetAuraIsRecordedAsTheDifferenceByTheAskingPerson() {
        execute("INSERT INTO discord_user (discord_id) VALUES ('" + PERSON.value() + "'), ('" + ADMIN.value() + "')");

        assertInstanceOf(Answer.Done.class, admin.setAura(PERSON, 40, Actor.person(ADMIN)));
        assertInstanceOf(Answer.Done.class, admin.setAura(PERSON, -10, Actor.person(ADMIN)));
        assertInstanceOf(Answer.Done.class, admin.setAura(PERSON, -10, Actor.person(ADMIN)));

        assertEquals(
                List.of("40 PERSON " + ADMIN.value(), "-50 PERSON " + ADMIN.value()),
                jdbi.withHandle(handle -> handle.createQuery(
                                "SELECT e.delta || ' ' || a.actor_kind || ' ' || a.actor_id FROM smp_aura_event e"
                                        + " JOIN smp_aura_admin a ON a.event_id = e.id ORDER BY e.at, e.delta DESC")
                        .mapTo(String.class)
                        .list()),
                "an unchanged value records nothing");
        assertEquals(
                "-10",
                jdbi.withHandle(handle -> handle.createQuery("SELECT aura::text FROM smp_player WHERE discord_id = :id")
                        .bind("id", PERSON.value())
                        .mapTo(String.class)
                        .one()));
    }

    @Test
    void setSpinsAreTheExtrasLeftBesideThoseUsed() {
        execute("INSERT INTO discord_user (discord_id) VALUES ('" + PERSON.value() + "')");

        assertInstanceOf(Answer.Done.class, admin.setSpins(PERSON, 2));
        execute("UPDATE smp_spin SET used = 5, granted = 6 WHERE discord_id = '" + PERSON.value() + "'");
        assertInstanceOf(Answer.Done.class, admin.setSpins(PERSON, 3));

        assertEquals(
                "8 5",
                jdbi.withHandle(handle -> handle.createQuery(
                                "SELECT granted || ' ' || used FROM smp_spin WHERE discord_id = :id")
                        .bind("id", PERSON.value())
                        .mapTo(String.class)
                        .one()));
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
