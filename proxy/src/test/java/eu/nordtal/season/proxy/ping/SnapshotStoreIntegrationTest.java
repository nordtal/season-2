package eu.nordtal.season.proxy.ping;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.database.RoundSeed;
import eu.nordtal.season.database.TestDatabase;
import eu.nordtal.season.database.network.NetworkSnapshot;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.LoggerFactory;

/**
 * The one query behind every MOTD placeholder, against PostgreSQL with the real migrations.
 *
 * Skips without Docker. Eliminated players come from {@code DEATH} rows; a team is in while any member has none.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SnapshotStoreIntegrationTest {
    private static DataSource dataSource;

    private RoundSeed seed;
    private SnapshotStore store;
    private UUID round;

    @BeforeAll
    static void startDatabase() {
        dataSource = TestDatabase.fresh().dataSource();
    }

    @BeforeEach
    void emptyDatabase() {
        seed = new RoundSeed(dataSource);
        execute("TRUNCATE TABLE hg_event, hg_game, registration, smp_contribution, smp_objective,"
                + " smp_milestone, smp_player, discord_user CASCADE");
        store = SnapshotStore.using(dataSource, LoggerFactory.getLogger(SnapshotStoreIntegrationTest.class));
    }

    @Test
    void anEmptyDatabaseAnswersZeroesRatherThanFailing() {
        // The state a fresh season is in for weeks: no game, no milestones, nobody registered.
        store.refresh();

        assertEquals(
                NetworkSnapshot.EMPTY,
                store.current(),
                "an empty database must produce exactly the snapshot a proxy that has never queried "
                        + "shows, or the MOTD changes appearance the first time the query succeeds");
    }

    @Test
    void anOpenRoundIsCountedByTeamsAndByPeopleOnThoseTeams() {
        seedGame();

        store.refresh();
        final NetworkSnapshot snapshot = store.current();

        assertEquals(2, snapshot.hgTeams());
        // An INVITED row is an unanswered question, not a participant; OWNER and ACCEPTED both count.
        assertEquals(3, snapshot.hgParticipants());
        assertEquals(0, snapshot.hgEliminated());
        assertEquals(3, snapshot.hgAlive());
        assertEquals(2, snapshot.hgTeamsAlive());
    }

    @Test
    void aDeathTakesAPlayerOutButATeamOnlyWithItsLastMember() {
        seedGame();
        startGame();

        // Alpha's owner dies. Alpha is still in, because its second member is alive.
        kill(DiscordId.of("100000000000000001"));
        store.refresh();

        assertEquals(1, store.current().hgEliminated());
        assertEquals(2, store.current().hgAlive(), "alive is participants minus eliminated, by construction");
        assertEquals(
                2, store.current().hgTeamsAlive(), "a team is still in while ANY of its full members has no DEATH row");

        // Alpha's second member dies too. Now the team is out.
        kill(DiscordId.of("100000000000000002"));
        store.refresh();

        assertEquals(2, store.current().hgEliminated());
        assertEquals(1, store.current().hgAlive());
        assertEquals(1, store.current().hgTeamsAlive());
        assertEquals(
                2, store.current().hgTeams(), "the registered team count does not shrink when a team is knocked out");
    }

    @Test
    void aDecidedGameEndsItsRoundAndEveryNumberWithIt() {
        // The current round is a query, so once its game is decided every number falls back to zero.
        seedGame();
        startGame();
        seed.execute("UPDATE hg_game SET state = 'DECIDED'");
        seed.execute("UPDATE registration SET state = 'ENDED'");

        store.refresh();

        assertEquals(0, store.current().hgTeams());
        assertEquals(0, store.current().hgParticipants());
    }

    @Test
    void anAbortedGamesDeathsAreForgottenOnceItsRoundIsOpenAgain() {
        // A restart aborts the game and the round plays again from the start, with everybody alive.
        seedGame();
        startGame();
        kill(DiscordId.of("100000000000000001"));
        seed.execute("UPDATE hg_game SET state = 'ABORTED'");
        seed.execute("UPDATE registration SET state = 'OPEN'");

        store.refresh();

        assertEquals(0, store.current().hgEliminated());
        assertEquals(3, store.current().hgAlive());
        assertEquals(2, store.current().hgTeamsAlive());
    }

    @Test
    void theActiveMilestoneAndItsProgressAreWhatTheSmpMotdShows() {
        execute("""
                INSERT INTO smp_milestone (key, state) VALUES
                    ('first-steps', 'UNLOCKED'),
                    ('the-nether', 'ACTIVE'),
                    ('the-end', 'LOCKED')
                """);
        // Half of one objective and all of another: 150 of 300 is 50%.
        execute("""
                INSERT INTO smp_objective (milestone_key, key, type, amount, target) VALUES
                    ('the-nether', 'blaze-rods', 'HAND_IN', 50, 200),
                    ('the-nether', 'obsidian', 'HAND_IN', 100, 100)
                """);

        store.refresh();
        final NetworkSnapshot snapshot = store.current();

        assertEquals("the-nether", snapshot.smpMilestone());
        assertEquals(50, snapshot.smpProgress());
        assertEquals(1, snapshot.smpMilestonesDone());
        assertEquals(3, snapshot.smpMilestones());
    }

    @Test
    void anOvershootingObjectiveCannotCarryTheOthersPastWhatWasAsked() {
        // least(amount, target) per objective, else an overshoot completes the milestone early.
        execute("INSERT INTO smp_milestone (key, state) VALUES ('the-nether', 'ACTIVE')");
        execute("""
                INSERT INTO smp_objective (milestone_key, key, type, amount, target) VALUES
                    ('the-nether', 'blaze-rods', 'HAND_IN', 10000, 100),
                    ('the-nether', 'obsidian', 'HAND_IN', 0, 100)
                """);

        store.refresh();

        assertEquals(50, store.current().smpProgress());
    }

    @Test
    void auraIsSummedSignedBecauseDeathsCostIt() {
        execute("""
                INSERT INTO discord_user (discord_id) VALUES
                    ('100000000000000001'), ('100000000000000002')
                """);
        execute("""
                INSERT INTO smp_player (discord_id, aura) VALUES
                    ('100000000000000001', 400), ('100000000000000002', -150)
                """);

        store.refresh();

        assertEquals(250, store.current().smpAuraTotal());
        assertEquals(2, store.current().smpPlayers());
    }

    @Test
    void aDeathAgainstSomebodyWhoNeverJoinedATeamIsNotAnElimination() {
        // eliminated is counted over the participants' set, else a non-playing DEATH row exceeds it.
        seedGame();
        startGame();
        kill(DiscordId.of("100000000000000003")); // the INVITED row on Alpha
        store.refresh();

        assertEquals(
                0,
                store.current().hgEliminated(),
                "an INVITED member is not a participant, so their death cannot eliminate one");
        assertEquals(3, store.current().hgAlive());
        assertEquals(2, store.current().hgTeamsAlive());
    }

    @Test
    void aFailedRefreshKeepsTheNumbersThatWereAlreadyThere() {
        // Refuses connections instead of dropping tables, which would break the schema for the next test.
        final AtomicBoolean unreachable = new AtomicBoolean();
        final SnapshotStore overAnOutage = SnapshotStore.using(
                failingWhen(unreachable), LoggerFactory.getLogger(SnapshotStoreIntegrationTest.class));

        seedGame();
        overAnOutage.refresh();
        assertEquals(2, overAnOutage.current().hgTeams());

        // The MOTD keeps what it last knew, so a failure is caught, not thrown.
        unreachable.set(true);
        overAnOutage.refresh();

        assertEquals(2, overAnOutage.current().hgTeams(), "a database hiccup must cost freshness and nothing else");

        unreachable.set(false);
        overAnOutage.refresh();
        assertEquals(2, overAnOutage.current().hgTeams(), "and the next tick is the retry");
    }

    // fixtures

    /** An open round: Alpha with an owner, an accepted partner and an open invitation, Beta with an owner. */
    private void seedGame() {
        round = seed.round("OPEN");
        final UUID alpha = seed.team(round, "Alpha");
        seed.member(alpha, "100000000000000001", "OWNER");
        seed.member(alpha, "100000000000000002", "ACCEPTED");
        seed.member(alpha, "100000000000000003", "INVITED");
        seed.member(seed.team(round, "Beta"), "100000000000000004", "OWNER");
    }

    /** Starts a game of the round, which closes it. */
    private void startGame() {
        seed.execute("UPDATE registration SET state = 'CLOSED' WHERE id = ?", round);
        seed.game(round, "RUNNING");
    }

    /** Writes the DEATH row an elimination in the game under way produces, all this query sees of one. */
    private void kill(final DiscordId discordId) {
        seed.execute("""
                INSERT INTO hg_event (game_id, type, victim_id)
                SELECT game.id, 'DEATH', member.id FROM hg_game game, team_member member
                WHERE game.state = 'RUNNING' AND member.discord_id = ?
                """, discordId.value());
    }

    /** The real pool, until the flag makes every {@code getConnection} fail like an unreachable database. */
    private static DataSource failingWhen(final AtomicBoolean unreachable) {
        return (DataSource) Proxy.newProxyInstance(
                DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class}, (instance, method, arguments) -> {
                    if (unreachable.get() && method.getName().equals("getConnection")) {
                        throw new SQLException("the database is unreachable");
                    }
                    try {
                        return method.invoke(dataSource, arguments);
                    } catch (final InvocationTargetException wrapped) {
                        throw wrapped.getCause();
                    }
                });
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
