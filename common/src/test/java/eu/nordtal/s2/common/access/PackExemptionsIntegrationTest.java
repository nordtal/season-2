package eu.nordtal.s2.common.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Tests the resource pack exemption against a real PostgreSQL, through to the row the proxy's login query returns.
 *
 * Skipped when no Docker daemon is reachable.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PackExemptionsIntegrationTest {

    private static final String ADMIN = "600000000000000001";
    private static final String PLAYER = "600000000000000002";
    private static final String STRANGER = "600000000000000009";
    private static final UUID PLAYER_MC = UUID.fromString("00000000-0000-0000-0000-000000000602");

    private static PostgreSQLContainer<?> postgres;
    private static PGSimpleDataSource dataSource;

    private PackExemptions exemptions;
    private AccessDirectory access;

    @BeforeAll
    static void startDatabase() {
        assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "No Docker daemon reachable - skipping the PostgreSQL-backed pack exemption tests");

        postgres = new PostgreSQLContainer<>("postgres:17-alpine")
                .withDatabaseName("access")
                .withUsername("access")
                .withPassword("access");
        postgres.start();

        dataSource = new PGSimpleDataSource();
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());

        AccessSchema.migrate(dataSource);
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
    void oneAdminAndOneLinkedPlayer() {
        execute("TRUNCATE TABLE admin_grant, account_link, discord_user CASCADE");
        execute("INSERT INTO discord_user (discord_id, admin, admin_granted_at) VALUES ('" + ADMIN + "', true, now())");
        execute("INSERT INTO discord_user (discord_id) VALUES ('" + PLAYER + "')");
        execute("INSERT INTO account_link (mc_uuid, discord_id) VALUES ('" + PLAYER_MC + "', '" + PLAYER + "')");
        exemptions = PackExemptions.using(dataSource);
        access = AccessDirectory.using(dataSource);
    }

    @Test
    void thePackIsWhatEveryPlayerGetsUntilAnAdminSaysOtherwise() {
        assertFalse(access.accessState(PLAYER_MC).packExempt(), "a fresh account came back exempt");
        assertFalse(
                access.accessState(UUID.fromString("00000000-0000-0000-0000-000000000999"))
                        .packExempt(),
                "an unlinked account came back exempt");
    }

    @Test
    void anExemptionReachesTheLoginStateAndRecordsWhoAndWhen() throws SQLException {
        assertEquals(PackExemptions.Outcome.CHANGED, exemptions.exempt(ADMIN, PLAYER));
        assertTrue(access.accessState(PLAYER_MC).packExempt());
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet row = statement.executeQuery(
                        "SELECT pack_exempt_by, pack_exempt_at FROM discord_user WHERE discord_id = '" + PLAYER
                                + "'")) {
            assertTrue(row.next());
            assertEquals(ADMIN, row.getString(1));
            assertTrue(row.getTimestamp(2) != null, "no time recorded");
        }
        assertEquals(PackExemptions.Outcome.UNCHANGED, exemptions.exempt(ADMIN, PLAYER));
    }

    @Test
    void enforcingTheWayItWasExemptedTakesItBack() {
        exemptions.exempt(ADMIN, PLAYER);
        assertEquals(PackExemptions.Outcome.CHANGED, exemptions.enforce(ADMIN, PLAYER));
        assertFalse(access.accessState(PLAYER_MC).packExempt());
        assertEquals(PackExemptions.Outcome.UNCHANGED, exemptions.enforce(ADMIN, PLAYER));
    }

    @Test
    void onlyAnAdminMayExemptAndOnlySomebodyKnown() {
        assertEquals(PackExemptions.Outcome.ACTOR_NOT_ADMIN, exemptions.exempt(PLAYER, PLAYER));
        assertEquals(PackExemptions.Outcome.ACTOR_NOT_ADMIN, exemptions.enforce(STRANGER, PLAYER));
        assertEquals(PackExemptions.Outcome.UNKNOWN, exemptions.exempt(ADMIN, STRANGER));
        assertFalse(access.accessState(PLAYER_MC).packExempt());
    }

    @Test
    void theDatabaseRefusesAnExemptionWithoutAnAdminBehindIt() {
        final SQLException refused = org.junit.jupiter.api.Assertions.assertThrows(
                SQLException.class,
                () -> executeChecked(
                        "UPDATE discord_user SET pack_exempt_at = now() WHERE discord_id = '" + PLAYER + "'"));
        assertTrue(refused.getMessage().contains("discord_user_pack_exempt_is_set_by_someone"), refused.getMessage());
    }

    private static void execute(final String sql) {
        try {
            executeChecked(sql);
        } catch (final SQLException e) {
            throw new IllegalStateException(sql, e);
        }
    }

    private static void executeChecked(final String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
