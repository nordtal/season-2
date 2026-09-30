package eu.nordtal.s2.hungergames.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.database.Jdbis;
import eu.nordtal.s2.database.TestDatabase;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Runs {@link HungerGamesDao#killCounts} against a real PostgreSQL with the real migrations.
 *
 * It checks the {@code bigint} mapping and the column names, and skips itself without Docker.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KillCountsIntegrationTest {
    private static DataSource dataSource;

    private HungerGamesDao dao;
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
        execute("TRUNCATE TABLE hg_event, hg_member, hg_team, hg_game, discord_user CASCADE");

        dao = Jdbis.over(dataSource).onDemand(HungerGamesDao.class);

        gameId = uuid("INSERT INTO hg_game (state) VALUES ('RUNNING') RETURNING id");
        final UUID teamId = uuid("INSERT INTO hg_team (game_id, name) VALUES ('" + gameId + "', 'reds') RETURNING id");
        alice = member(teamId, DiscordId.of("100000000000000001"));
        bob = member(teamId, DiscordId.of("100000000000000002"));
        carol = member(teamId, DiscordId.of("100000000000000003"));
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
        execute("INSERT INTO hg_event (game_id, type, actor_id) VALUES ('" + gameId + "', 'BORDER_SHRINK', NULL)");
        execute("INSERT INTO hg_event (game_id, type, actor_id, victim_id) VALUES ('" + gameId + "', 'DEATH', '" + alice
                + "', '" + bob + "')");

        final UUID otherGame = uuid("INSERT INTO hg_game (state) VALUES ('DECIDED') RETURNING id");
        final UUID otherTeam =
                uuid("INSERT INTO hg_team (game_id, name) VALUES ('" + otherGame + "', 'blues') RETURNING id");
        execute("INSERT INTO hg_member (team_id, game_id, discord_id) VALUES ('" + otherTeam + "', '" + otherGame
                + "', '100000000000000001')");
        execute("INSERT INTO hg_event (game_id, type, actor_id) SELECT '" + otherGame
                + "', 'KILL', id FROM hg_member WHERE game_id = '" + otherGame + "'");

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

    private UUID member(final UUID teamId, final DiscordId discordId) {
        execute("INSERT INTO discord_user (discord_id) VALUES ('" + discordId + "')");
        return uuid("INSERT INTO hg_member (team_id, game_id, discord_id) VALUES ('" + teamId + "', '" + gameId + "', '"
                + discordId + "') RETURNING id");
    }

    private void kill(final UUID actor, final UUID victim) {
        execute("INSERT INTO hg_event (game_id, type, actor_id, victim_id) VALUES ('" + gameId + "', 'KILL', '" + actor
                + "', '" + victim + "')");
    }

    private static void execute(final String sql) {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (final SQLException exception) {
            throw new IllegalStateException(sql, exception);
        }
    }

    private static UUID uuid(final String sql) {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getObject(1, UUID.class);
        } catch (final SQLException exception) {
            throw new IllegalStateException(sql, exception);
        }
    }
}
