package eu.nordtal.s2.hungergames.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.database.DatabaseRole;
import eu.nordtal.s2.database.Jdbis;
import eu.nordtal.s2.database.RoundSeed;
import eu.nordtal.s2.database.TestDatabase;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
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
    private HungerGamesDao dao;
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
        dao = Jdbis.over(database.dataSourceAs(DatabaseRole.HUNGER_GAMES)).onDemand(HungerGamesDao.class);

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
        assertEquals(GameState.COUNTDOWN, dao.gameUnderWay().orElseThrow().state());
        assertEquals(Optional.empty(), dao.openRound(), "the lobby waits in an open round only");
        assertEquals(Optional.empty(), dao.startGame(round), "a second start of the same round goes nowhere");
    }

    @Test
    void aRestartAbortsTheGameAndOpensItsRoundAgainWithItsTeams() {
        dao.markReady(round, IDA);
        final UUID game = dao.startGame(round).orElseThrow();
        dao.release(game);

        final HgGame aborted = dao.abortInterrupted().orElseThrow();

        assertEquals(game, aborted.id());
        assertEquals(GameState.RUNNING, aborted.state(), "the state it had when the server stopped");
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
        assertTrue(dao.markReady(round, IDA));
        assertTrue(dao.markReady(round, IDA));
        assertFalse(dao.markReady(round, INVITED), "an unanswered invite is not on the team");
        assertFalse(dao.markReady(round, DiscordId.of("100000000000000009")));

        assertEquals(Map.of(ida, true, ole, false), readiness());
    }

    @Test
    void aTeamStartedAnewKeepsOneColour() {
        dao.setTeamColour(team, 0xFF0000, "red");
        dao.setTeamColour(team, 0x00FF00, "green");

        assertEquals("green", seed.text("SELECT colour_named FROM hg_team_colour WHERE team_id = ?", team));
    }

    private String roundState() {
        return seed.text("SELECT state FROM registration WHERE id = ?", round);
    }

    private Map<UUID, Boolean> readiness() {
        return dao.roster(round).stream().collect(Collectors.toMap(RosterEntry::memberId, RosterEntry::ready));
    }
}
