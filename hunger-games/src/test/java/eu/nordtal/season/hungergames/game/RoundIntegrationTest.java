package eu.nordtal.season.hungergames.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.database.DatabaseRole;
import eu.nordtal.season.database.Jdbis;
import eu.nordtal.season.database.RoundSeed;
import eu.nordtal.season.database.TestDatabase;
import eu.nordtal.season.hungergames.roster.RosterDao;
import eu.nordtal.season.hungergames.roster.RosterEntry;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * A round's life on the real schema, as hunger-games' own role: started, aborted by a restart, started anew, decided.
 *
 * It skips itself without Docker.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RoundIntegrationTest {

    private static final DiscordId IDA = DiscordId.of("100000000000000001");
    private static final DiscordId OLE = DiscordId.of("100000000000000002");
    private static final DiscordId INVITED = DiscordId.of("100000000000000003");

    private static TestDatabase database;

    private RoundSeed seed;
    private GameDao dao;
    private RosterDao rosters;
    private UUID round;
    private UUID team;
    private UUID ida;
    private UUID ole;

    @BeforeAll
    static void startDatabase() {
        database = TestDatabase.fresh();
    }

    @BeforeEach
    void openRound() {
        seed = new RoundSeed(database.dataSource());
        seed.execute("TRUNCATE TABLE hg_event, hg_game, registration, discord_user CASCADE");
        final Jdbi jdbi = Jdbis.over(database.dataSourceAs(DatabaseRole.HUNGER_GAMES));
        dao = jdbi.onDemand(GameDao.class);
        rosters = jdbi.onDemand(RosterDao.class);

        round = seed.round("OPEN");
        team = seed.team(round, "Foxes");
        ida = seed.member(team, IDA.value(), "OWNER");
        ole = seed.member(team, OLE.value(), "ACCEPTED");
        seed.member(team, INVITED.value(), "INVITED");
    }

    @Test
    void aStartClosesTheRoundToTheBotAndCountsDown() {
        assertTrue(dao.startGame(round).isPresent());

        assertEquals("CLOSED", roundState());
        assertEquals(HgGameState.COUNTDOWN, dao.gameUnderWay().orElseThrow().state());
        assertEquals(Optional.empty(), dao.openRound(), "the lobby waits in an open round only");
        assertEquals(Optional.empty(), dao.startGame(round), "a second start of the same round goes nowhere");
    }

    @Test
    void aRestartAbortsTheGameAndOpensItsRoundAgainWithItsTeams() {
        rosters.markReady(round, IDA);
        final UUID game = dao.startGame(round).orElseThrow();
        dao.release(game);

        final HgGame aborted = dao.abortInterrupted().orElseThrow();

        assertEquals(game, aborted.id());
        assertEquals(HgGameState.RUNNING, aborted.state(), "the state it had when the server stopped");
        assertEquals("ABORTED", seed.text("SELECT state FROM hg_game WHERE id = ?", game));
        assertEquals(Optional.empty(), dao.gameUnderWay());
        assertEquals(Optional.of(round), dao.openRound(), "the same round, for an admin to start anew");
        assertEquals(Map.of(ida, true, ole, false), readiness(), "the teams and who said they are ready stay");

        final UUID again = dao.startGame(round).orElseThrow();
        assertNotEquals(game, again);
        assertEquals(again, dao.gameUnderWay().orElseThrow().id());
    }

    @Test
    void aRestartWithNoGameUnderWayChangesNothing() {
        dao.decideGame(dao.startGame(round).orElseThrow(), ida);

        assertEquals(Optional.empty(), dao.abortInterrupted());
        assertEquals("ENDED", roundState());
    }

    @Test
    void aDecidedGameEndsItsRoundSoTheNextRegistrationOpensANewOne() {
        final UUID game = dao.startGame(round).orElseThrow();
        dao.release(game);

        dao.decideGame(game, ida);

        assertEquals("DECIDED", seed.text("SELECT state FROM hg_game WHERE id = ?", game));
        assertEquals(ida.toString(), seed.text("SELECT winner_member_id FROM hg_game WHERE id = ?", game));
        assertEquals("ENDED", roundState());
        assertEquals(Optional.empty(), dao.currentRound());
    }

    @Test
    void onlyAMemberOnATeamSaysReadyAndSayingItTwiceIsStillYes() {
        assertTrue(rosters.markReady(round, IDA));
        assertTrue(rosters.markReady(round, IDA));
        assertFalse(rosters.markReady(round, INVITED), "an unanswered invite is not on the team");
        assertFalse(rosters.markReady(round, DiscordId.of("100000000000000009")));

        assertEquals(Map.of(ida, true, ole, false), readiness());
    }

    @Test
    void aTeamStartedAnewKeepsOneColour() {
        rosters.setTeamColour(team, 0xFF0000, "red");
        rosters.setTeamColour(team, 0x00FF00, "green");

        assertEquals("green", seed.text("SELECT colour_named FROM hg_team_colour WHERE team_id = ?", team));
    }

    private String roundState() {
        return seed.text("SELECT state FROM registration WHERE id = ?", round);
    }

    private Map<UUID, Boolean> readiness() {
        return rosters.roster(round).stream().collect(Collectors.toMap(RosterEntry::memberId, RosterEntry::ready));
    }
}
