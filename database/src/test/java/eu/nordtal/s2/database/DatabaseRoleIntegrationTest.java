package eu.nordtal.s2.database;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/** Holds what V1 grants each role: a statement it needs goes through, one it has no business with is refused. */
class DatabaseRoleIntegrationTest {

    /** PostgreSQL's SQLSTATE for "permission denied". */
    private static final String DENIED = "42501";

    private static final List<DatabaseRole> LOGINS = List.of(
            DatabaseRole.DISCORD_BOT,
            DatabaseRole.PROXY,
            DatabaseRole.LIMBO,
            DatabaseRole.HUNGER_GAMES,
            DatabaseRole.SMP,
            DatabaseRole.STEWARD_UI);

    private static TestDatabase database;

    @BeforeAll
    static void freshDatabase() {
        database = TestDatabase.fresh();
    }

    private record Case(DatabaseRole role, String sql, boolean allowed) {}

    private static Case may(final DatabaseRole role, final String sql) {
        return new Case(role, sql, true);
    }

    private static Case mayNot(final DatabaseRole role, final String sql) {
        return new Case(role, sql, false);
    }

    @Test
    void everyRoleReachesWhatItOwnsAndReadsAndNothingElse() {
        final List<Case> cases = new ArrayList<>(List.of(
                may(DatabaseRole.PROXY, "UPDATE account_link SET mc_name = 'x' WHERE mc_uuid = gen_random_uuid()"),
                mayNot(DatabaseRole.PROXY, "UPDATE account_link SET discord_id = 'x' WHERE false"),
                mayNot(
                        DatabaseRole.PROXY,
                        "INSERT INTO steward_session (id, csrf, created_at, expires_at) VALUES ('x', 'y', now(), now())"),
                may(DatabaseRole.LIMBO, "SELECT count(*) FROM discord_user"),
                mayNot(DatabaseRole.LIMBO, "INSERT INTO discord_user (discord_id) VALUES ('1')"),
                may(
                        DatabaseRole.SMP,
                        "INSERT INTO smp_poi (name, world, x, y, z, created_by) VALUES ('a', 'w', 0, 0, 0, '1')"),
                mayNot(DatabaseRole.SMP, "INSERT INTO hg_event (game_id, type) VALUES (gen_random_uuid(), 'x')"),
                may(DatabaseRole.HUNGER_GAMES, "INSERT INTO hg_event (game_id, type) VALUES (gen_random_uuid(), 'x')"),
                mayNot(DatabaseRole.HUNGER_GAMES, "UPDATE smp_player SET aura = 0 WHERE false"),
                may(DatabaseRole.STEWARD_UI, "INSERT INTO update_request (kind, actor_kind) VALUES ('UPDATE', 'HOST')"),
                mayNot(DatabaseRole.STEWARD_UI, "DELETE FROM update_request WHERE false"),
                mayNot(
                        DatabaseRole.STEWARD_UI,
                        "INSERT INTO service_plugin (service, artifact, project_id, file_prefix, title)"
                                + " VALUES ('a', 'b', 'c', 'd', 'e')"),
                may(DatabaseRole.DISCORD_BOT, "SELECT count(*) FROM flyway_schema_history"),
                may(DatabaseRole.DISCORD_BOT, "UPDATE access_request SET status = 'DONE' WHERE false"),
                mayNot(
                        DatabaseRole.DISCORD_BOT,
                        "INSERT INTO access_request (kind, subject, actor_kind, expires) VALUES ('GRANT', '1', 'HOST', now())"),
                mayNot(DatabaseRole.DISCORD_BOT, "SELECT count(*) FROM steward_session")));
        for (final DatabaseRole role : LOGINS) {
            cases.add(may(role, "INSERT INTO audit_log (action) VALUES ('X')"));
            cases.add(mayNot(role, "UPDATE audit_log SET detail = '' WHERE false"));
            cases.add(mayNot(role, "DELETE FROM audit_log WHERE false"));
            cases.add(mayNot(role, "SELECT count(*) FROM service_plugin"));
        }
        assertAll(cases.stream().map(DatabaseRoleIntegrationTest::check));
    }

    private static Executable check(final Case c) {
        return () -> assertEquals(
                c.allowed(),
                allowed(c.role(), c.sql()),
                c.role().roleName() + (c.allowed() ? " may: " : " may not: ") + c.sql());
    }

    /** Runs the statement and rolls it back; any failure but a refused permission counts as allowed. */
    private static boolean allowed(final DatabaseRole role, final String sql) throws SQLException {
        try (Connection connection = database.dataSourceAs(role).getConnection()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
                return true;
            } catch (final SQLException refused) {
                return !DENIED.equals(refused.getSQLState());
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    void theBackupRoleReadsEverythingThroughTheSocketAndWritesNothing() {
        assertEquals("", database.socketAs(DatabaseRole.BACKUP, "SELECT count(*) FROM steward_session"));
        assertTrue(database.socketAs(DatabaseRole.BACKUP, "DELETE FROM steward_session WHERE false")
                .contains("permission denied"));
    }

    @Test
    void thePlaceholdersNameEveryRoleOnceUnderTheGivenPrefix() {
        final Map<String, String> named = DatabaseRole.placeholders("scratch_");
        assertEquals(DatabaseRole.values().length, named.size());
        assertEquals("scratch_hunger_games", named.get("role_hunger_games"));
    }
}
