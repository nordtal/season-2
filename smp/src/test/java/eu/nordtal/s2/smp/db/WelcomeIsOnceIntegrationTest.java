package eu.nordtal.s2.smp.db;

import org.flywaydb.core.Flyway;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The season's opening moment happens exactly once per player, against a real PostgreSQL.
 *
 * <h2>Why this needs a container</h2>
 * The whole of "exactly once" is one statement, and its entire value is what PostgreSQL does with
 * the <em>second</em> call: {@code INSERT ... ON CONFLICT DO UPDATE ... WHERE NOT welcome_shown}
 * affects zero rows, which is how "already welcomed" is told apart from "just welcomed" without a
 * read-then-write that two sessions can race. There is no in-memory stand-in for that, and the
 * defaulted column added by {@code V16} is part of what is being checked - the moment has to be
 * available to every account that already exists.
 *
 * <h2>Why "once" is the part worth a test at all</h2>
 * Nothing else in the network could notice this going wrong. A welcome shown twice is something one
 * person mentions once and nobody writes down; a welcome shown never is invisible by definition,
 * because the only person who could report it does not know it was meant to happen. Every other part
 * of the moment - the frames, the blindness, the cancel - is at least visible to somebody standing
 * there.
 *
 * <p>It <b>skips itself</b> when no Docker daemon is reachable, so a green build on a machine
 * without Docker proves none of it.</p>
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
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
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
        execute("INSERT INTO discord_user (discord_id) VALUES ('" + PLAYER + "'), ('"
                + SOMEBODY_ELSE + "')");

        dao = Jdbi.create(dataSource)
                .installPlugin(new SqlObjectPlugin())
                .installPlugin(new PostgresPlugin())
                .onDemand(SmpDao.class);
    }

    @Test
    @DisplayName("the first join takes it and every join after it does not")
    void onlyTheFirstJoinGetsIt() {
        assertTrue(dao.claimWelcome(PLAYER), "the first join of the season is the moment");
        assertFalse(dao.claimWelcome(PLAYER), "the second join must not be");
        assertFalse(dao.claimWelcome(PLAYER));
        assertTrue(flag(PLAYER), "the flag is what survives a restart; without it every start of"
                + " the server is somebody's first join again");
    }

    @Test
    @DisplayName("a player with no smp_player row yet is welcomed, which is the ordinary case")
    void thereIsNoRowToStartWith() {
        // A row appears in smp_player when somebody EARNS something, so on the join this runs on
        // there is normally none at all. The INSERT half of the claim is what makes that work, and
        // it is the half that is easy to lose in a refactor towards a plain UPDATE.
        assertEquals(0, rows(PLAYER));
        assertTrue(dao.claimWelcome(PLAYER));
        assertEquals(1, rows(PLAYER));
    }

    @Test
    @DisplayName("an existing player is welcomed without their aura being touched")
    void anExistingRowKeepsItsAura() {
        dao.addAura(PLAYER, 40, "ADMIN", null);
        assertEquals(40, dao.auraOf(PLAYER).orElseThrow());

        assertTrue(dao.claimWelcome(PLAYER));

        assertEquals(40, dao.auraOf(PLAYER).orElseThrow(),
                "the claim upserts, so the ON CONFLICT branch must set the flag and nothing else -"
                        + " an INSERT that overwrote the row would zero somebody's season");
    }

    @Test
    @DisplayName("one player's welcome is not another's")
    void theClaimIsPerPlayer() {
        assertTrue(dao.claimWelcome(PLAYER));

        assertTrue(dao.claimWelcome(SOMEBODY_ELSE),
                "the claim is keyed by discord_id; anything table-wide would welcome the first"
                        + " player of the season and nobody else, ever");
    }

    @Test
    @DisplayName("two sessions racing each other produce one moment")
    void onlyOneOfTwoSimultaneousJoinsWins() throws Exception {
        // The real shape of this is a reconnect inside a second, or a proxy that moves somebody to
        // the SMP twice while the first join's language lookup is still in flight. Both joins reach
        // the claim at the same time on different pool connections, which is exactly what is
        // simulated here.
        final int racers = 8;
        final ExecutorService pool = Executors.newFixedThreadPool(racers);
        final CyclicBarrier together = new CyclicBarrier(racers);
        try {
            final List<Callable<Boolean>> claims = new ArrayList<>();
            for (int i = 0; i < racers; i++) {
                claims.add(() -> {
                    together.await();
                    return dao.claimWelcome(PLAYER);
                });
            }
            int won = 0;
            for (final Future<Boolean> outcome : pool.invokeAll(claims)) {
                if (outcome.get()) {
                    won++;
                }
            }
            assertEquals(1, won, "exactly one of " + racers + " simultaneous joins may be told it"
                    + " is the first one - the others have to get zero rows back and show nothing");
        } finally {
            pool.shutdownNow();
        }
    }

    private boolean flag(final String discordId) {
        return query("SELECT welcome_shown FROM smp_player WHERE discord_id = '" + discordId + "'");
    }

    private int rows(final String discordId) {
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
