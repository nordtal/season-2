package eu.nordtal.season.smp.wheel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.database.Jdbis;
import eu.nordtal.season.database.TestDatabase;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Puts a wheel spin back, against a real PostgreSQL with the real migrations.
 *
 * A refund restores the same kind of spin, writes a null date as a typed null and changes nothing on a second call.
 *
 * The test skips itself without Docker.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SpinRefundIntegrationTest {

    private static final String DISCORD_ID = "100000000000000042";
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 4);
    private static final LocalDate YESTERDAY = TODAY.minusDays(1);
    private static DataSource dataSource;

    private SpinDao dao;

    @BeforeAll
    static void startDatabase() {
        dataSource = TestDatabase.fresh().dataSource();
    }

    @BeforeEach
    void freshPlayer() {
        execute("TRUNCATE TABLE smp_spin, discord_user CASCADE");
        execute("INSERT INTO discord_user (discord_id) VALUES ('" + DISCORD_ID + "')");

        dao = Jdbis.over(dataSource).onDemand(SpinDao.class);
    }

    @Test
    void theFirstFreeSpinIsRefundedToNull() {
        assertTrue(dao.takeFreeSpin(DiscordId.of(DISCORD_ID), TODAY).isPresent(), "the spin has to be taken first");
        assertFalse(spins().hasFree(TODAY), "and be gone");

        // JDBI binds the null date as a typed null.
        dao.restoreFreeSpin(DiscordId.of(DISCORD_ID), null, TODAY);

        assertEquals(null, spins().lastFree(), "a refunded first spin is a spin never taken");
        assertTrue(spins().hasFree(TODAY), "and the player can spin again");
    }

    @Test
    void aLaterFreeSpinIsRefundedToItsPreviousDay() {
        execute("INSERT INTO smp_spin (discord_id, last_free) VALUES ('" + DISCORD_ID + "', '"
                + YESTERDAY + "') ON CONFLICT (discord_id) DO UPDATE SET last_free = '"
                + YESTERDAY + "'");
        assertTrue(dao.takeFreeSpin(DiscordId.of(DISCORD_ID), TODAY).isPresent());

        dao.restoreFreeSpin(DiscordId.of(DISCORD_ID), YESTERDAY, TODAY);

        assertEquals(YESTERDAY, spins().lastFree());
        assertTrue(spins().hasFree(TODAY));
    }

    @Test
    void theFreeRefundIsIdempotent() {
        assertTrue(dao.takeFreeSpin(DiscordId.of(DISCORD_ID), TODAY).isPresent());

        dao.restoreFreeSpin(DiscordId.of(DISCORD_ID), null, TODAY);
        // The second call changes nothing, because {@code last_free} no longer holds today.
        dao.restoreFreeSpin(DiscordId.of(DISCORD_ID), null, TODAY);

        assertEquals(1, spins().available(TODAY), "one spin was taken, so one spin comes back");
    }

    @Test
    void aRefundedFreeSpinCanBeTakenAgainOnce() {
        assertTrue(dao.takeFreeSpin(DiscordId.of(DISCORD_ID), TODAY).isPresent());
        dao.restoreFreeSpin(DiscordId.of(DISCORD_ID), null, TODAY);

        assertTrue(dao.takeFreeSpin(DiscordId.of(DISCORD_ID), TODAY).isPresent(), "the refund really gave it back");
        assertFalse(dao.takeFreeSpin(DiscordId.of(DISCORD_ID), TODAY).isPresent(), "and it is one spin, not two");
    }

    @Test
    void anEarnedSpinIsRefunded() {
        dao.grantSpins(DiscordId.of(DISCORD_ID), 2);
        execute("UPDATE smp_spin SET last_free = '" + TODAY + "'");
        assertTrue(dao.takeEarnedSpin(DiscordId.of(DISCORD_ID)).isPresent());
        assertEquals(1, spins().extras());

        dao.restoreEarnedSpin(DiscordId.of(DISCORD_ID));

        assertEquals(2, spins().extras(), "the earned pool is where an earned spin belongs");
        assertFalse(
                spins().hasFree(TODAY),
                "and the free spin stays taken - refunding the wrong kind would be a free spin a day");
    }

    @Test
    void theEarnedRefundRespectsTheCheckConstraint() {
        dao.grantSpins(DiscordId.of(DISCORD_ID), 1);

        // A refund without a spin used changes nothing.
        dao.restoreEarnedSpin(DiscordId.of(DISCORD_ID));

        assertEquals(1, spins().extras());
    }

    private Spins spins() {
        return dao.spinsOf(DiscordId.of(DISCORD_ID))
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
