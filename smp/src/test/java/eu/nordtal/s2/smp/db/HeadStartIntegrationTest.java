package eu.nordtal.s2.smp.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;
import org.flywaydb.core.Flyway;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * The start event winner's head start, against a real PostgreSQL running the real migrations.
 *
 * <b>Why this needs a container</b>
 *
 * Every part of it is SQL. The lookup is a join across two tables written by a different module; the claim is one
 * {@code INSERT ... ON CONFLICT DO UPDATE WHERE}, whose entire value is what PostgreSQL does with the second call -
 * it affects zero rows, which is how "already paid" is told apart from "just paid" without a read-then-write anybody
 * can race. And the payout is a transaction over two more tables. There is no seam here that an in-memory stand-in
 * could hold.
 *
 * What is being protected is not subtle: <b>this pays out once per season, to one person, and it cannot be taken
 * back.</b> A second join a second later must not produce a second elytra, and a practice game played months
 * afterwards must not move the prize to somebody else.
 *
 * It <b>skips itself</b> when no Docker daemon is reachable, so a green build on a machine without Docker proves
 * none of it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HeadStartIntegrationTest {

    private static final String WINNER = "100000000000000042";
    private static final String SOMEBODY_ELSE = "100000000000000043";
    private static final int AURA = 150;
    private static final String REASON = "HG_WINNER";

    private static PostgreSQLContainer<?> postgres;
    private static PGSimpleDataSource dataSource;

    private SmpDao dao;

    @BeforeAll
    static void startDatabase() {
        assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "No Docker daemon reachable - skipping the PostgreSQL-backed head start tests");

        postgres = new PostgreSQLContainer<>("postgres:17-alpine")
                .withDatabaseName("access")
                .withUsername("access")
                .withPassword("access");
        postgres.start();

        dataSource = new PGSimpleDataSource();
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());

        Flyway.configure(HeadStartIntegrationTest.class.getClassLoader())
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
    void freshSeason() {
        execute("TRUNCATE TABLE hg_game, smp_player, smp_aura_event, discord_user CASCADE");
        execute("INSERT INTO discord_user (discord_id) VALUES ('" + WINNER + "'), ('" + SOMEBODY_ELSE + "')");

        dao = Jdbi.create(dataSource)
                .installPlugin(new SqlObjectPlugin())
                .installPlugin(new PostgresPlugin())
                .onDemand(SmpDao.class);
    }

    @Test
    void anUndecidedGameYieldsNobody() {
        game("RUNNING", WINNER, "2026-09-01", true);

        assertEquals(
                Optional.empty(),
                dao.startEventWinner(),
                "the head start is paid from a DECIDED game; anything else is a game in progress");
    }

    @Test
    void aDecidedGameWithNoWinnerYieldsNobody() {
        // A tiebreak with no winner and every participant dead both write DECIDED with winner_member_id null.
        game("DECIDED", WINNER, "2026-09-01", false);

        assertEquals(
                Optional.empty(),
                dao.startEventWinner(),
                "winner_member_id IS NULL has to drop out of the join, not come back as a row");
    }

    @Test
    void theWinnerIsResolvedToADiscordId() {
        game("DECIDED", WINNER, "2026-09-01", true);

        assertEquals(Optional.of(WINNER), dao.startEventWinner());
    }

    @Test
    void theEarliestDecidedGameWins() {
        game("DECIDED", WINNER, "2026-09-01", true);
        game("DECIDED", SOMEBODY_ELSE, "2026-11-20", true);

        // Ordering it the other way would pay whoever won most recently, months after the real payout, unrecoverably.
        assertEquals(
                Optional.of(WINNER),
                dao.startEventWinner(),
                "the start event is the FIRST decided game, not the newest");
    }

    @Test
    void theClaimInsertsAPlayerWhoHasNeverEarnedAnything() {
        // The winner has played no SMP, so no aura row exists yet; an UPDATE-only claim would silently say "no".
        assertEquals(0, playerRows(), "the winner starts with no row at all");

        assertTrue(dao.grantHeadStart(WINNER, AURA, REASON));

        assertEquals(1, playerRows());
        assertTrue(granted(WINNER));
        assertEquals(AURA, aura(WINNER));
        assertEquals(1, auraEvents(WINNER), "and the balance can be explained");
    }

    @Test
    void theClaimIsTakenExactlyOnce() {
        assertTrue(dao.grantHeadStart(WINNER, AURA, REASON));

        assertFalse(
                dao.grantHeadStart(WINNER, AURA, REASON),
                "the flag is the gate: a row already carrying true matches nothing in the ON"
                        + " CONFLICT ... WHERE, so the statement affects no rows");

        assertEquals(AURA, aura(WINNER), "and above all it must not have been booked twice");
        assertEquals(1, auraEvents(WINNER));
    }

    @Test
    void theHeadStartAddsToWhatIsAlreadyThere() {
        // Reachable normally: the winner joins before DECIDED, earns aura for an advancement, then the flag is set.
        execute("INSERT INTO smp_player (discord_id, aura) VALUES ('" + WINNER + "', 20)");

        assertTrue(dao.grantHeadStart(WINNER, AURA, REASON));

        assertEquals(20 + AURA, aura(WINNER));
        assertTrue(granted(WINNER));
    }

    @Test
    void zeroAuraIsAConfiguredValueAndNotAnAccident() {
        // hg-winner-aura may be set to 0 and keep only the items, but it must still claim, or items repeat every join.
        assertTrue(dao.grantHeadStart(WINNER, 0, REASON));

        assertTrue(granted(WINNER));
        assertEquals(0, auraEvents(WINNER), "an event saying +0 explains nothing and is noise");
        assertFalse(dao.grantHeadStart(WINNER, 0, REASON), "and it is still taken exactly once");
    }

    /** One game with one team and one member, optionally decided in that member's favour. */
    private void game(final String state, final String discordId, final String created, final boolean withWinner) {
        final String gameId =
                query("INSERT INTO hg_game (state, created) VALUES ('" + state + "', '" + created + "') RETURNING id");
        final String teamId =
                query("INSERT INTO hg_team (game_id, name) VALUES ('" + gameId + "', 'Team') RETURNING id");
        final String memberId = query("INSERT INTO hg_member (team_id, game_id, discord_id)" + " VALUES ('" + teamId
                + "', '" + gameId + "', '" + discordId + "') RETURNING id");
        if (withWinner) {
            execute("UPDATE hg_game SET winner_member_id = '" + memberId + "' WHERE id = '" + gameId + "'");
        }
    }

    /**
     * Read as a boolean rather than parsed out of text.
     *
     * PostgreSQL renders a {@code boolean} as {@code t} / {@code f}, and {@code Boolean.parseBoolean("t")} is
     * {@code false} - so a text round trip here reports every successful claim as a failed one. Found by this test
     * failing on a correct implementation.
     */
    private boolean granted(final String discordId) {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT hg_winner_reward_granted FROM"
                        + " smp_player WHERE discord_id = '" + discordId + "'")) {
            return rows.next() && rows.getBoolean(1);
        } catch (final SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private int aura(final String discordId) {
        return Integer.parseInt(query("SELECT aura FROM smp_player WHERE discord_id = '" + discordId + "'"));
    }

    private int auraEvents(final String discordId) {
        return Integer.parseInt(query("SELECT count(*) FROM smp_aura_event WHERE discord_id = '" + discordId
                + "' AND reason = '" + REASON + "'"));
    }

    private int playerRows() {
        return Integer.parseInt(query("SELECT count(*) FROM smp_player"));
    }

    private static void execute(final String sql) {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (final SQLException failure) {
            throw new IllegalStateException(sql, failure);
        }
    }

    /** The first column of the first row, as text - enough for an id, a count or a boolean. */
    private static String query(final String sql) {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            if (!rows.next()) {
                throw new IllegalStateException("no row from: " + sql);
            }
            return rows.getString(1);
        } catch (final SQLException failure) {
            throw new IllegalStateException(sql, failure);
        }
    }
}
