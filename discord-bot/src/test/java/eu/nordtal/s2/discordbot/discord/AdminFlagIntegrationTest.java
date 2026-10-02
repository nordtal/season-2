package eu.nordtal.s2.discordbot.discord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import eu.nordtal.jcore.persistence.sql.Database;
import eu.nordtal.jcore.persistence.sql.DatabaseConfig;
import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.database.Jdbis;
import eu.nordtal.s2.database.TestDatabase;
import eu.nordtal.s2.database.access.AccessDirectory;
import eu.nordtal.s2.database.access.AdminTree;
import java.time.Clock;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * What the admin tree writes is what {@link AdminFlagDao} reads back, against a real PostgreSQL.
 *
 * Skipped when no Docker daemon is reachable.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdminFlagIntegrationTest {

    private static final String USER = "200000000000000001";
    private static final String STRANGER = "200000000000000002";

    private static TestDatabase postgres;
    private static Database database;

    private AccessDirectory access;
    private AdminTree tree;
    private AdminFlagDao dao;

    @BeforeAll
    static void startDatabase() {
        postgres = TestDatabase.fresh();

        database = Database.create(DatabaseConfig.of(postgres.jdbcUrl(), postgres.username(), postgres.password()));
        database.jdbi().installPlugin(Jdbis.ids());
        database.migrate();
    }

    @AfterAll
    static void stopDatabase() {
        if (database != null) {
            database.close();
        }
    }

    @BeforeEach
    void clean() {
        assumeTrue(database != null);
        database.jdbi()
                .useHandle(handle -> handle.execute("TRUNCATE access_grant, payment_request, expiry_notice, "
                        + "account_link, link_code, audit_log, admin_grant, discord_user CASCADE"));
        access = AccessDirectory.using(database.dataSource(), Clock.systemUTC());
        tree = AdminTree.using(database.dataSource());
        dao = database.jdbi().onDemand(AdminFlagDao.class);
    }

    @Test
    void anAccountTheBotHasNeverWrittenAboutHasNoFlagAtAll() {
        assertEquals(Optional.empty(), dao.isAdmin(DiscordId.of(STRANGER)));
        assertFalse(AdminFlagDao.admits(dao.isAdmin(DiscordId.of(STRANGER))), "and therefore may not switch the phase");
    }

    @Test
    void aUserTheBotKnowsButHasNeverMadeAnAdminIsNotOne() {
        // The column is NOT NULL DEFAULT false, so a row written by any other path answers false.
        access.ensureUser(DiscordId.of(USER));

        assertEquals(Optional.of(false), dao.isAdmin(DiscordId.of(USER)));
        assertFalse(AdminFlagDao.admits(dao.isAdmin(DiscordId.of(USER))));
    }

    @Test
    void theRootsRowIsCreatedByTheClaimWhenNothingElseWroteIt() {
        // An admin who never bought or linked anything still has to be able to act as an admin.
        tree.claimRootIfNobody(DiscordId.of(USER));

        assertEquals(Optional.of(true), dao.isAdmin(DiscordId.of(USER)));
        assertTrue(AdminFlagDao.admits(dao.isAdmin(DiscordId.of(USER))));
    }

    @Test
    void leavingTheGuildClearsTheFlag() {
        tree.claimRootIfNobody(DiscordId.of(USER));
        tree.dropWithBranch(DiscordId.of(USER));

        assertEquals(Optional.of(false), dao.isAdmin(DiscordId.of(USER)));
        assertFalse(
                AdminFlagDao.admits(dao.isAdmin(DiscordId.of(USER))),
                "a stale true is what would let an ex-admin switch the season phase");
    }

    @Test
    void theFlagIsPerAccountAndDoesNotLeakToAnybodyElse() {
        tree.claimRootIfNobody(DiscordId.of(USER));
        access.ensureUser(DiscordId.of(STRANGER));

        assertTrue(AdminFlagDao.admits(dao.isAdmin(DiscordId.of(USER))));
        assertFalse(AdminFlagDao.admits(dao.isAdmin(DiscordId.of(STRANGER))));
    }
}
