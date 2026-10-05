package eu.nordtal.season.smp.welcome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.database.Jdbis;
import eu.nordtal.season.database.TestDatabase;
import eu.nordtal.season.smp.aura.AuraDao;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * The season's opening moment happens exactly once per player, against a real PostgreSQL.
 *
 * "Once" is one {@code INSERT ... ON CONFLICT} whose second call affects no rows; skips itself without Docker.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WelcomeIsOnceIntegrationTest {

    private static final String PLAYER = "100000000000000042";
    private static final String SOMEBODY_ELSE = "100000000000000043";
    private static DataSource dataSource;

    private WelcomeDao dao;
    private AuraDao aura;

    @BeforeAll
    static void startDatabase() {
        dataSource = TestDatabase.fresh().dataSource();
    }

    @BeforeEach
    void freshSeason() {
        execute("TRUNCATE TABLE smp_player, smp_aura_event, discord_user CASCADE");
        execute("INSERT INTO discord_user (discord_id) VALUES ('" + PLAYER + "'), ('" + SOMEBODY_ELSE + "')");

        dao = Jdbis.over(dataSource).onDemand(WelcomeDao.class);
        aura = Jdbis.over(dataSource).onDemand(AuraDao.class);
    }

    @Test
    void onlyTheFirstJoinGetsIt() {
        assertTrue(dao.claimWelcome(DiscordId.of(PLAYER)), "the first join of the season is the moment");
        assertFalse(dao.claimWelcome(DiscordId.of(PLAYER)), "the second join must not be");
        assertFalse(dao.claimWelcome(DiscordId.of(PLAYER)));
        assertTrue(
                flag(DiscordId.of(PLAYER)),
                "the flag is what survives a restart; without it every start of"
                        + " the server is somebody's first join again");
    }

    @Test
    void thereIsNoRowToStartWith() {
        // smp_player gets a row only when somebody EARNS something; the INSERT half is the easy one to lose.
        assertEquals(0, rows(DiscordId.of(PLAYER)));
        assertTrue(dao.claimWelcome(DiscordId.of(PLAYER)));
        assertEquals(1, rows(DiscordId.of(PLAYER)));
    }

    @Test
    void anExistingRowKeepsItsAura() {
        aura.addAura(DiscordId.of(PLAYER), 40, "ADMIN", null);
        assertEquals(40, aura.auraOf(DiscordId.of(PLAYER)).orElseThrow());

        assertTrue(dao.claimWelcome(DiscordId.of(PLAYER)));

        assertEquals(
                40,
                aura.auraOf(DiscordId.of(PLAYER)).orElseThrow(),
                "the claim upserts, so the ON CONFLICT branch must set the flag and nothing else -"
                        + " an INSERT that overwrote the row would zero somebody's season");
    }

    @Test
    void theClaimIsPerPlayer() {
        assertTrue(dao.claimWelcome(DiscordId.of(PLAYER)));

        assertTrue(
                dao.claimWelcome(DiscordId.of(SOMEBODY_ELSE)),
                "the claim is keyed by discord_id; anything table-wide would welcome the first"
                        + " player of the season and nobody else, ever");
    }

    @Test
    void onlyOneOfTwoSimultaneousJoinsWins() throws Exception {
        // A reconnect inside a second, or a proxy moving somebody twice mid-lookup: both joins race the same claim.
        final int racers = 8;
        final ExecutorService pool = Executors.newFixedThreadPool(racers);
        final CyclicBarrier together = new CyclicBarrier(racers);
        try {
            final List<Callable<Boolean>> claims = new ArrayList<>();
            for (int i = 0; i < racers; i++) {
                claims.add(() -> {
                    together.await();
                    return dao.claimWelcome(DiscordId.of(PLAYER));
                });
            }
            int won = 0;
            for (final Future<Boolean> outcome : pool.invokeAll(claims)) {
                if (outcome.get()) {
                    won++;
                }
            }
            assertEquals(
                    1,
                    won,
                    "exactly one of " + racers + " simultaneous joins may be told it"
                            + " is the first one - the others have to get zero rows back and show nothing");
        } finally {
            pool.shutdownNow();
        }
    }

    private boolean flag(final DiscordId discordId) {
        return query("SELECT welcome_shown FROM smp_player WHERE discord_id = '" + discordId + "'");
    }

    private int rows(final DiscordId discordId) {
        return count("SELECT count(*) FROM smp_player WHERE discord_id = '" + discordId + "'");
    }

    private static boolean query(final String sql) {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            return result.next() && result.getBoolean(1);
        } catch (final SQLException failure) {
            throw new IllegalStateException(sql, failure);
        }
    }

    private static int count(final String sql) {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            return result.next() ? result.getInt(1) : 0;
        } catch (final SQLException failure) {
            throw new IllegalStateException(sql, failure);
        }
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
