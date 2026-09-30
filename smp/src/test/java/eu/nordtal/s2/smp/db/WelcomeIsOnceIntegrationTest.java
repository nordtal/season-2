package eu.nordtal.s2.smp.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.database.Jdbis;
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
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * The season's opening moment happens exactly once per player, against a real PostgreSQL.
 *
 * "Once" is one {@code INSERT ... ON CONFLICT} whose second call affects no rows; skips itself without Docker.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WelcomeIsOnceIntegrationTest {

    private static final String PLAYER = "100000000000000042";
    private static final String SOMEBODY_ELSE = "100000000000000043";

    private static PostgreSQLContainer<?> postgres;
    private static PGSimpleDataSource dataSource;

    private SmpDao dao;

    @BeforeAll
    static void startDatabase() {
        assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "No Docker daemon reachable - skipping the PostgreSQL-backed welcome tests");

        postgres = new PostgreSQLContainer<>("postgres:17-alpine")
                .withDatabaseName("access")
                .withUsername("access")
                .withPassword("access");
        postgres.start();

        dataSource = new PGSimpleDataSource();
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());

        Flyway.configure(WelcomeIsOnceIntegrationTest.class.getClassLoader())
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
        execute("TRUNCATE TABLE smp_player, smp_aura_event, discord_user CASCADE");
        execute("INSERT INTO discord_user (discord_id) VALUES ('" + PLAYER + "'), ('" + SOMEBODY_ELSE + "')");

        dao = Jdbis.over(dataSource).onDemand(SmpDao.class);
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
        dao.addAura(DiscordId.of(PLAYER), 40, "ADMIN", null);
        assertEquals(40, dao.auraOf(DiscordId.of(PLAYER)).orElseThrow());

        assertTrue(dao.claimWelcome(DiscordId.of(PLAYER)));

        assertEquals(
                40,
                dao.auraOf(DiscordId.of(PLAYER)).orElseThrow(),
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
