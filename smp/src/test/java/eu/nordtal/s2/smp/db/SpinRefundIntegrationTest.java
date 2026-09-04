package eu.nordtal.s2.smp.db;

import eu.nordtal.s2.smp.wheel.Spins;

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
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Putting a wheel spin back, against a real PostgreSQL running the real migrations.
 *
 * <h2>Why the refund exists</h2>
 * The spin is spent in SQL <em>before</em> the prize is drawn and long before the animation draws
 * its first frame - deliberately, because an animation that could stop anywhere else would be a
 * second, disagreeing answer about one spin. The cost of that ordering is two paths that end with a
 * spent row and an empty hand: a player who disconnects between the commit and the next tick, and a
 * {@code wheel-prizes} entry naming an item this server does not know. Both used to log a warning
 * and leave the player a spin poorer; a warning in a console is not something a player can spend.
 * Found by review, 2026-09-04.
 *
 * <h2>Why it needs a container</h2>
 * All three things that can go wrong here are properties of the database, not of Java.
 * {@code last_free} is a nullable {@code date} and the refund of a player's <em>first ever</em> free
 * spin writes {@code null} into it - which is where PostgreSQL answers "could not determine data
 * type of parameter" unless the statement casts, and no in-memory test can tell you that. The free
 * refund has to be idempotent and the earned one has to respect
 * {@code smp_spin_used_not_negative}. And a refund must put back <em>the same kind</em> of spin
 * that was taken: giving an earned spin back as a free one would hand out a spin a day.
 *
 * <p>It <b>skips itself</b> when no Docker daemon is reachable, so a green build on a machine
 * without Docker proves none of it.
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
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
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

        // The real migrations off the classpath - :common is shaded into this module, so
        // db/migration is exactly where the plugin finds them on a server too.
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
    @DisplayName("a first-ever free spin goes back to never-taken, null and all")
    void theFirstFreeSpinIsRefundedToNull() {
        assertTrue(dao.takeFreeSpin(DISCORD_ID, TODAY).isPresent(), "the spin has to be taken first");
        assertFalse(spins().hasFree(TODAY), "and be gone");

        // null is the value the row held a millisecond earlier. Measured 2026-09-04: JDBI binds it
        // as a typed null and the statement passes with or without its CAST, so this line proves the
        // null PATH rather than the cast - which is the half that matters, because a refund that
        // threw here would be the very first spin of a player's season.
        dao.restoreFreeSpin(DISCORD_ID, null, TODAY);

        assertEquals(null, spins().lastFree(), "a refunded first spin is a spin never taken");
        assertTrue(spins().hasFree(TODAY), "and the player can spin again");
    }

    @Test
    @DisplayName("a free spin taken on a later day goes back to the day before it")
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
    @DisplayName("refunding a free spin twice hands back one spin, not two")
    void theFreeRefundIsIdempotent() {
        assertTrue(dao.takeFreeSpin(DISCORD_ID, TODAY).isPresent());

        dao.restoreFreeSpin(DISCORD_ID, null, TODAY);
        // The second call is the one that matters: last_free is no longer TODAY, so the guard makes
        // it change nothing. Without it a second refund would be free, and the wheel would be a
        // machine that pays for being closed twice.
        dao.restoreFreeSpin(DISCORD_ID, null, TODAY);

        assertEquals(1, spins().available(TODAY), "one spin was taken, so one spin comes back");
    }

    @Test
    @DisplayName("a refunded free spin is spendable again, and only once")
    void aRefundedFreeSpinCanBeTakenAgainOnce() {
        assertTrue(dao.takeFreeSpin(DISCORD_ID, TODAY).isPresent());
        dao.restoreFreeSpin(DISCORD_ID, null, TODAY);

        assertTrue(dao.takeFreeSpin(DISCORD_ID, TODAY).isPresent(), "the refund really gave it back");
        assertFalse(dao.takeFreeSpin(DISCORD_ID, TODAY).isPresent(), "and it is one spin, not two");
    }

    @Test
    @DisplayName("an earned spin goes back to the pool it came from")
    void anEarnedSpinIsRefunded() {
        dao.grantSpins(DISCORD_ID, 2);
        execute("UPDATE smp_spin SET last_free = '" + TODAY + "'");
        assertTrue(dao.takeEarnedSpin(DISCORD_ID).isPresent());
        assertEquals(1, spins().extras());

        dao.restoreEarnedSpin(DISCORD_ID);

        assertEquals(2, spins().extras(), "the earned pool is where an earned spin belongs");
        assertFalse(spins().hasFree(TODAY),
                "and the free spin stays taken - refunding the wrong kind would be a free spin a day");
    }

    @Test
    @DisplayName("an earned refund cannot push used below zero")
    void theEarnedRefundRespectsTheCheckConstraint() {
        dao.grantSpins(DISCORD_ID, 1);

        // smp_spin_used_not_negative would abort the transaction, and the caller of a refund has no
        // sensible answer to that. The guard is what makes an unpaired call a no-op instead.
        dao.restoreEarnedSpin(DISCORD_ID);

        assertEquals(1, spins().extras());
    }

    // --- helpers ---------------------------------------------------------------------------

    private Spins spins() {
        return dao.spinsOf(DISCORD_ID).orElseThrow(
                () -> new AssertionError("the row disappeared, which no statement here can do"));
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
