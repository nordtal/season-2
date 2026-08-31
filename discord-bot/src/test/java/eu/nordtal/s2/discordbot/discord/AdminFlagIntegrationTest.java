package eu.nordtal.s2.discordbot.discord;

import eu.nordtal.jcore.persistence.sql.Database;
import eu.nordtal.jcore.persistence.sql.DatabaseConfig;
import eu.nordtal.s2.common.access.AccessDirectory;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The admin flag end to end inside this module: what {@link GuildState} writes through
 * {@code AccessDirectory#setAdmin} is what {@link AdminFlagDao} reads back, and what
 * {@code PhaseCommand} then authorises on.
 * <p>
 * Against a real PostgreSQL running the real migration, because everything that can be wrong here
 * is in the schema: the column {@code V4} added, the upsert that writes it, and a column name in a
 * hand-written {@code SELECT} which nothing else would catch until an admin was told they are not
 * one.
 * </p>
 * <p>
 * Driven by hand from {@link BeforeAll} rather than through {@code @Testcontainers}, for the same
 * reason as {@code PaymentRequestIntegrationTest}: that extension is built against JUnit 5 and this
 * repo is on the JUnit 6 BOM. It skips itself when no Docker daemon is reachable.
 * </p>
 * <p>
 * What this <b>cannot</b> prove: that the Discord role is mirrored correctly. Whether a
 * {@code GuildMemberRoleRemove} really arrives, and whether the reconcile sees the member cache it
 * expects, needs a real guild.
 * </p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdminFlagIntegrationTest {

    private static final String USER = "200000000000000001";
    private static final String STRANGER = "200000000000000002";

    private static PostgreSQLContainer<?> postgres;
    private static Database database;

    private AccessDirectory access;
    private AdminFlagDao dao;

    @BeforeAll
    static void startDatabase() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "No Docker daemon reachable - skipping the PostgreSQL-backed tests");

        postgres = new PostgreSQLContainer<>("postgres:17-alpine")
                .withDatabaseName("access")
                .withUsername("access")
                .withPassword("access");
        postgres.start();

        database = Database.create(DatabaseConfig.of(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        database.migrate();
    }

    @AfterAll
    static void stopDatabase() {
        if (database != null) {
            database.close();
        }
        if (postgres != null) {
            postgres.stop();
        }
    }

    @BeforeEach
    void clean() {
        assumeTrue(database != null);
        database.jdbi().useHandle(handle -> handle.execute(
                "TRUNCATE access_grant, payment_request, expiry_notice, payment_notice, "
                        + "account_link, link_code, audit_log, discord_user CASCADE"));
        access = AccessDirectory.using(database.dataSource());
        dao = database.jdbi().onDemand(AdminFlagDao.class);
    }

    @Test
    @DisplayName("an account the bot has never written about has no flag at all")
    void unknownAccountHasNoFlag() {
        assertEquals(Optional.empty(), dao.isAdmin(STRANGER));
        assertFalse(PhaseCommand.maySwitch(dao.isAdmin(STRANGER)),
                "and therefore may not switch the phase");
    }

    @Test
    @DisplayName("a user the bot knows but has never made an admin is not one")
    void knownAccountDefaultsToNotAnAdmin() {
        // V4 added the column NOT NULL DEFAULT false, so a row written by any other path - a link,
        // a purchase - answers false rather than nothing.
        access.ensureUser(USER);

        assertEquals(Optional.of(false), dao.isAdmin(USER));
        assertFalse(PhaseCommand.maySwitch(dao.isAdmin(USER)));
    }

    @Test
    @DisplayName("the mirror creates the row when the admin role arrives before anything else")
    void mirroringTheRoleCreatesTheRow() {
        // An admin who has never bought anything and never linked an account still has to be able
        // to use /phase set.
        access.setAdmin(USER, true);

        assertEquals(Optional.of(true), dao.isAdmin(USER));
        assertTrue(PhaseCommand.maySwitch(dao.isAdmin(USER)));
    }

    @Test
    @DisplayName("losing the role clears the flag - it is a projection, not a grant")
    void losingTheRoleClearsTheFlag() {
        access.setAdmin(USER, true);
        access.setAdmin(USER, false);

        assertEquals(Optional.of(false), dao.isAdmin(USER));
        assertFalse(PhaseCommand.maySwitch(dao.isAdmin(USER)),
                "a stale true is what would let an ex-admin switch the season phase");
    }

    @Test
    @DisplayName("the flag is per account and does not leak to anybody else")
    void theFlagIsPerAccount() {
        access.setAdmin(USER, true);
        access.ensureUser(STRANGER);

        assertTrue(PhaseCommand.maySwitch(dao.isAdmin(USER)));
        assertFalse(PhaseCommand.maySwitch(dao.isAdmin(STRANGER)));
    }
}
