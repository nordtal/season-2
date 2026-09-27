package eu.nordtal.s2.smp.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
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
 * Putting a wheel spin back, against a real PostgreSQL running the real migrations.
 *
 * A refund restores the same kind of spin, casts its null date and stays idempotent; skips itself without Docker.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SpinRefundIntegrationTest {

    private static final String DISCORD_ID = "100000000000000042";
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 4);
    private static final LocalDate YESTERDAY = TODAY.minusDays(1);

    private static PostgreSQLContainer<?> postgres;
    private static PGSimpleDataSource dataSource;

    private SmpDao dao;

    @BeforeAll
    static void startDatabase() {
        assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "No Docker daemon reachable - skipping the PostgreSQL-backed spin refund tests");

        postgres = new PostgreSQLContainer<>("postgres:17-alpine")
                .withDatabaseName("access")
                .withUsername("access")
                .withPassword("access");
        postgres.start();

        dataSource = new PGSimpleDataSource();
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());

        // The real migrations off the classpath: :common is shaded in, so db/migration is where a server finds them.
        Flyway.configure(SpinRefundIntegrationTest.class.getClassLoader())
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
    void freshPlayer() {
        execute("TRUNCATE TABLE smp_spin, discord_user CASCADE");
        execute("INSERT INTO discord_user (discord_id) VALUES ('" + DISCORD_ID + "')");

        dao = Jdbi.create(dataSource)
                .installPlugin(new SqlObjectPlugin())
                .installPlugin(new PostgresPlugin())
                .onDemand(SmpDao.class);
    }

    @Test
    void theFirstFreeSpinIsRefundedToNull() {
        assertTrue(dao.takeFreeSpin(DISCORD_ID, TODAY).isPresent(), "the spin has to be taken first");
        assertFalse(spins().hasFree(TODAY), "and be gone");

        // null is the value the row held a moment earlier; JDBI binds it as a typed null, proving the null path.
        dao.restoreFreeSpin(DISCORD_ID, null, TODAY);

        assertEquals(null, spins().lastFree(), "a refunded first spin is a spin never taken");
        assertTrue(spins().hasFree(TODAY), "and the player can spin again");
    }

    @Test
    void aLaterFreeSpinIsRefundedToItsPreviousDay() {
        execute("INSERT INTO smp_spin (discord_id, last_free) VALUES ('" + DISCORD_ID + "', '"
                + YESTERDAY + "') ON CONFLICT (discord_id) DO UPDATE SET last_free = '"
                + YESTERDAY + "'");
        assertTrue(dao.takeFreeSpin(DISCORD_ID, TODAY).isPresent());

        dao.restoreFreeSpin(DISCORD_ID, YESTERDAY, TODAY);

        assertEquals(YESTERDAY, spins().lastFree());
        assertTrue(spins().hasFree(TODAY));
    }

    @Test
    void theFreeRefundIsIdempotent() {
        assertTrue(dao.takeFreeSpin(DISCORD_ID, TODAY).isPresent());

        dao.restoreFreeSpin(DISCORD_ID, null, TODAY);
        // The second call matters: last_free is no longer TODAY, so the guard makes it a no-op, not a free refund.
        dao.restoreFreeSpin(DISCORD_ID, null, TODAY);

        assertEquals(1, spins().available(TODAY), "one spin was taken, so one spin comes back");
    }

    @Test
    void aRefundedFreeSpinCanBeTakenAgainOnce() {
        assertTrue(dao.takeFreeSpin(DISCORD_ID, TODAY).isPresent());
        dao.restoreFreeSpin(DISCORD_ID, null, TODAY);

        assertTrue(dao.takeFreeSpin(DISCORD_ID, TODAY).isPresent(), "the refund really gave it back");
        assertFalse(dao.takeFreeSpin(DISCORD_ID, TODAY).isPresent(), "and it is one spin, not two");
    }

    @Test
    void anEarnedSpinIsRefunded() {
        dao.grantSpins(DISCORD_ID, 2);
        execute("UPDATE smp_spin SET last_free = '" + TODAY + "'");
        assertTrue(dao.takeEarnedSpin(DISCORD_ID).isPresent());
        assertEquals(1, spins().extras());

        dao.restoreEarnedSpin(DISCORD_ID);

        assertEquals(2, spins().extras(), "the earned pool is where an earned spin belongs");
        assertFalse(
                spins().hasFree(TODAY),
                "and the free spin stays taken - refunding the wrong kind would be a free spin a day");
    }

    @Test
    void theEarnedRefundRespectsTheCheckConstraint() {
        dao.grantSpins(DISCORD_ID, 1);

        // smp_spin_used_not_negative would abort the transaction; the guard makes an unpaired call a no-op instead.
        dao.restoreEarnedSpin(DISCORD_ID);

        assertEquals(1, spins().extras());
    }

    private Spins spins() {
        return dao.spinsOf(DISCORD_ID)
                .orElseThrow(() -> new AssertionError("the row disappeared, which no statement here can do"));
    }

    private static void execute(final String sql) {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (final SQLException e) {
            throw new IllegalStateException("cannot run " + sql, e);
        }
    }
}
