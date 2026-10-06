package eu.nordtal.season.hungergames.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.database.Jdbis;
import eu.nordtal.season.database.RoundSeed;
import eu.nordtal.season.database.TestDatabase;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Runs {@link GameDao#killCounts} against a real PostgreSQL with the real migrations.
 *
 * It checks the {@code bigint} mapping and the column names, and skips itself without Docker.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KillCountsIntegrationTest {
    private static DataSource dataSource;

    private RoundSeed seed;
    private GameDao dao;
    private UUID gameId;
    private UUID alice;
    private UUID bob;
    private UUID carol;

    @BeforeAll
    static void startDatabase() {
        dataSource = TestDatabase.fresh().dataSource();
    }

    @BeforeEach
    void freshGame() {
        seed = new RoundSeed(dataSource);
        seed.execute("TRUNCATE TABLE hg_event, hg_game, registration, discord_user CASCADE");

        dao = Jdbis.over(dataSource).onDemand(GameDao.class);

        final UUID round = seed.round("CLOSED");
        gameId = seed.game(round, "RUNNING");
        final UUID teamId = seed.team(round, "reds");
        alice = seed.member(teamId, "100000000000000001", "OWNER");
        bob = seed.member(teamId, "100000000000000002", "ACCEPTED");
        carol = seed.member(teamId, "100000000000000003", "ACCEPTED");
    }

    @Test
    void oneGroupedQueryAnswersWhatOneQueryPerMemberUsedTo() {
        kill(alice, bob);
        kill(alice, carol);
        kill(bob, carol);

        final Map<UUID, Integer> tally = dao.killCounts(gameId);

        assertEquals(2, tally.get(alice), "count(*) is bigint; this is the mapping that has to hold");
        assertEquals(1, tally.get(bob));
        assertEquals(
                Map.of(alice, 2, bob, 1),
                tally,
                "carol killed nobody and must simply be absent - the ceremony prints only the"
                        + " members above zero, so a zero row would be an extra line saying nothing");
    }

    @Test
    void theTallyAndTheTiebreakNeverDisagree() {
        kill(alice, carol);
        kill(alice, bob);
        kill(bob, carol);

        final Map<UUID, Integer> tally = dao.killCounts(gameId);
        for (final UUID member : java.util.List.of(alice, bob, carol)) {
            assertEquals(
                    dao.killCount(gameId, member),
                    tally.getOrDefault(member, 0),
                    "killCount decides who wins a tie and killCounts is what players are shown; two"
                            + " answers is a scoreboard contradicting the result printed above it");
        }
    }

    @Test
    void onlyThisGamesKillEventsCount() {
        kill(alice, bob);
        seed.execute("INSERT INTO hg_event (game_id, type, actor_id) VALUES (?, 'BORDER_SHRINK', NULL)", gameId);
        seed.execute(
                "INSERT INTO hg_event (game_id, type, actor_id, victim_id) VALUES (?, 'DEATH', ?, ?)",
                gameId,
                alice,
                bob);

        final UUID otherRound = seed.round("ENDED");
        final UUID otherGame = seed.game(otherRound, "DECIDED");
        final UUID otherMember = seed.member(seed.team(otherRound, "blues"), "100000000000000001", "OWNER");
        seed.execute("INSERT INTO hg_event (game_id, type, actor_id) VALUES (?, 'KILL', ?)", otherGame, otherMember);

        assertEquals(
                Map.of(alice, 1),
                dao.killCounts(gameId),
                "a season runs more than one game, and a DEATH is not a KILL");
    }

    @Test
    void aGameNobodyKilledInAnswersAnEmptyMapNotNull() {
        final Map<UUID, Integer> tally = dao.killCounts(gameId);
        assertTrue(
                tally.isEmpty(),
                "the ceremony iterates this; null would be an exception in front"
                        + " of everybody at the end of the event");
    }

    private void kill(final UUID actor, final UUID victim) {
        seed.execute(
                "INSERT INTO hg_event (game_id, type, actor_id, victim_id) VALUES (?, 'KILL', ?, ?)",
                gameId,
                actor,
                victim);
    }
}
