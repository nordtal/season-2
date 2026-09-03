package eu.nordtal.s2.networkcontrol.ping;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;
import org.slf4j.LoggerFactory;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The one query behind every MOTD placeholder, against a real PostgreSQL running the real
 * migrations.
 *
 * <p>This is the only thing that can say anything about that query at all. It is a page of scalar
 * subqueries over tables <b>two other modules</b> own ({@code V5}'s hunger games, {@code V6}'s SMP),
 * it runs on a timer where a failure is caught and logged rather than thrown, and it renders into a
 * server-list entry nobody is watching in a test. A typo in it would be invisible until somebody
 * noticed the browser had been showing zeroes for a week.
 *
 * <p>It also pins the two derivations that are not simply counts: eliminated players come from
 * {@code hg_event}'s {@code DEATH} rows rather than from any stored flag, and a team is "still in"
 * while any of its members has no such row.
 *
 * <p>Skips itself without Docker, like every other integration test here - so a green build on a
 * machine without a daemon proves nothing about this file.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SnapshotStoreIntegrationTest {

    private static PostgreSQLContainer<?> postgres;
    private static PGSimpleDataSource dataSource;

    private SnapshotStore store;

    @BeforeAll
    static void startDatabase() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "No Docker daemon reachable - skipping the PostgreSQL-backed MOTD snapshot tests");

        postgres = new PostgreSQLContainer<>("postgres:17-alpine")
                .withDatabaseName("access")
                .withUsername("access")
                .withPassword("access");
        postgres.start();

        dataSource = new PGSimpleDataSource();
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());

        Flyway.configure(SnapshotStoreIntegrationTest.class.getClassLoader())
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) {
            postgres.stop();
            postgres = null;
        }
        dataSource = null;
    }

    @BeforeEach
    void emptyDatabase() {
        execute("TRUNCATE TABLE hg_event, hg_member, hg_team, hg_game, smp_contribution, smp_objective,"
                + " smp_milestone, smp_player, discord_user CASCADE");
        store = SnapshotStore.using(dataSource, LoggerFactory.getLogger(SnapshotStoreIntegrationTest.class));
    }

    @Test
    void anEmptyDatabaseAnswersZeroesRatherThanFailing() {
        // The state a fresh season is in for weeks, and the state the MOTD has to survive: no game,
        // no milestones, nobody registered.
        store.refresh();

        assertEquals(NetworkSnapshot.EMPTY, store.current(),
                "an empty database must produce exactly the snapshot a proxy that has never queried "
                        + "shows, or the MOTD changes appearance the first time the query succeeds");
    }

    @Test
    void aRegisteredGameIsCountedByTeamsAndByPeopleOnThoseTeams() {
        seedGame();

        store.refresh();
        final NetworkSnapshot snapshot = store.current();

        assertEquals("REGISTRATION", snapshot.hgState());
        assertEquals(2, snapshot.hgTeams());
        // Four rows, three of them on a team: an INVITED row is an unanswered question and not a
        // participant, which is the distinction V5 draws and this query has to draw with it. The
        // other two states, OWNER and ACCEPTED, both count.
        assertEquals(3, snapshot.hgParticipants());
        assertEquals(0, snapshot.hgEliminated());
        assertEquals(3, snapshot.hgAlive());
        assertEquals(2, snapshot.hgTeamsAlive());
    }

    @Test
    void aDeathTakesAPlayerOutButATeamOnlyWithItsLastMember() {
        seedGame();
        execute("UPDATE hg_game SET state = 'RUNNING'");

        // Alpha's owner dies. Alpha is still in, because its second member is alive.
        kill("100000000000000001");
        store.refresh();

        assertEquals(1, store.current().hgEliminated());
        assertEquals(2, store.current().hgAlive(), "alive is participants minus eliminated, by construction");
        assertEquals(2, store.current().hgTeamsAlive(),
                "a team is still in while ANY of its full members has no DEATH row");

        // Alpha's second member dies too. Now the team is out.
        kill("100000000000000002");
        store.refresh();

        assertEquals(2, store.current().hgEliminated());
        assertEquals(1, store.current().hgAlive());
        assertEquals(1, store.current().hgTeamsAlive());
        assertEquals(2, store.current().hgTeams(),
                "the registered team count does not shrink when a team is knocked out");
    }

    @Test
    void aDecidedGameStopsBeingTheCurrentOne() {
        // hg_game has a partial unique index allowing at most one non-DECIDED row, so "the current
        // game" is a query and not a stored pointer. Once it is decided there is no current game and
        // every number falls back to zero - which is what the browser should say between games.
        seedGame();
        execute("UPDATE hg_game SET state = 'DECIDED'");

        store.refresh();

        assertEquals("", store.current().hgState());
        assertEquals(0, store.current().hgTeams());
        assertEquals(0, store.current().hgParticipants());
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
        // least(amount, target) per objective. Without it a hand-in of 10 000 against a target of
        // 100 would report the whole milestone as complete while its other objectives sat untouched.
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
    void aFailedRefreshKeepsTheNumbersThatWereAlreadyThere() {
        seedGame();
        store.refresh();
        assertEquals(2, store.current().hgTeams());

        // The tables are gone. The MOTD keeps showing what it last knew rather than blanking, which
        // is the whole reason a failure here is caught and not thrown.
        execute("DROP TABLE hg_event, hg_member, hg_team, hg_game CASCADE");
        store.refresh();

        assertEquals(2, store.current().hgTeams(),
                "a database hiccup must cost freshness and nothing else");
    }

    // ---------------------------------------------------------------- fixtures

    /**
     * Two teams: Alpha with an owner, an accepted partner and one unanswered invitation; Beta with
     * an owner. Three participants out of four rows.
     */
    private void seedGame() {
        execute("""
                INSERT INTO discord_user (discord_id) VALUES
                    ('100000000000000001'), ('100000000000000002'),
                    ('100000000000000003'), ('100000000000000004')
                """);
        execute("INSERT INTO hg_game (state) VALUES ('REGISTRATION')");
        execute("""
                INSERT INTO hg_team (game_id, name)
                SELECT id, 'Alpha' FROM hg_game
                """);
        execute("""
                INSERT INTO hg_team (game_id, name)
                SELECT id, 'Beta' FROM hg_game
                """);
        execute("""
                INSERT INTO hg_member (team_id, game_id, discord_id, state)
                SELECT team.id, team.game_id, '100000000000000001', 'OWNER'
                FROM hg_team team WHERE team.name = 'Alpha'
                """);
        execute("""
                INSERT INTO hg_member (team_id, game_id, discord_id, state)
                SELECT team.id, team.game_id, '100000000000000002', 'ACCEPTED'
                FROM hg_team team WHERE team.name = 'Alpha'
                """);
        execute("""
                INSERT INTO hg_member (team_id, game_id, discord_id, state)
                SELECT team.id, team.game_id, '100000000000000003', 'INVITED'
                FROM hg_team team WHERE team.name = 'Alpha'
                """);
        execute("""
                INSERT INTO hg_member (team_id, game_id, discord_id, state)
                SELECT team.id, team.game_id, '100000000000000004', 'OWNER'
                FROM hg_team team WHERE team.name = 'Beta'
                """);
    }

    /** Writes the DEATH row an elimination produces, which is all this query ever sees of one. */
    private void kill(final String discordId) {
        execute("""
                INSERT INTO hg_event (game_id, type, victim_id)
                SELECT game_id, 'DEATH', id FROM hg_member WHERE discord_id = '%s'
                """.formatted(discordId));
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
