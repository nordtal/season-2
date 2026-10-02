package eu.nordtal.s2.database;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.database.network.SnapshotDirectory;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
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
            DatabaseRole.STEWARD);

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
                mayNot(DatabaseRole.SMP, "SELECT count(*) FROM hg_game"),
                may(DatabaseRole.HUNGER_GAMES, "INSERT INTO hg_event (game_id, type) VALUES (gen_random_uuid(), 'x')"),
                mayNot(DatabaseRole.HUNGER_GAMES, "UPDATE smp_player SET aura = 0 WHERE false"),
                may(
                        DatabaseRole.STEWARD,
                        "INSERT INTO steward_inbox (kind, payload, actor_kind) VALUES ('UPDATE', '{\"services\": []}', 'HOST')"),
                mayNot(DatabaseRole.STEWARD, "DELETE FROM steward_inbox WHERE false"),
                mayNot(
                        DatabaseRole.STEWARD,
                        "INSERT INTO service_plugin (service, artifact, project_id, file_prefix, title)"
                                + " VALUES ('a', 'b', 'c', 'd', 'e')"),
                may(DatabaseRole.DISCORD_BOT, "SELECT count(*) FROM flyway_schema_history"),
                may(DatabaseRole.DISCORD_BOT, "UPDATE bot_inbox SET status = 'DONE' WHERE false"),
                mayNot(
                        DatabaseRole.DISCORD_BOT,
                        "INSERT INTO bot_inbox (kind, payload, actor_kind) VALUES ('REVOKE', '{}', 'HOST')"),
                mayNot(DatabaseRole.STEWARD, "UPDATE bot_inbox SET status = 'DONE' WHERE false"),
                may(
                        DatabaseRole.SMP,
                        "INSERT INTO bot_inbox (kind, payload, actor_kind) VALUES ('ANNOUNCE', '{}', 'STEWARD')"),
                may(
                        DatabaseRole.DISCORD_BOT,
                        "INSERT INTO bank_inbox (kind, payload, actor_kind) VALUES ('CANCEL_TAB', '{}', 'HOST')"),
                mayNot(DatabaseRole.DISCORD_BOT, "UPDATE bank_inbox SET status = 'DONE' WHERE false"),
                may(
                        DatabaseRole.STEWARD,
                        "INSERT INTO bank_inbox (kind, payload, actor_kind) VALUES ('CANCEL_TAB', '{}', 'HOST')"),
                may(DatabaseRole.STEWARD, "UPDATE bank_inbox SET status = 'DONE' WHERE false"),
                mayNot(DatabaseRole.DISCORD_BOT, "SELECT count(*) FROM steward_session")));
        cases.addAll(stewardsOwnLoops());
        for (final DatabaseRole role : LOGINS) {
            cases.add(may(role, "INSERT INTO audit_log (action) VALUES ('X')"));
            cases.add(mayNot(role, "UPDATE audit_log SET detail = '' WHERE false"));
            cases.add(mayNot(role, "DELETE FROM audit_log WHERE false"));
            cases.add(mayNot(role, "SELECT count(*) FROM service_plugin"));
            cases.add(mayNot(role, "SELECT count(*) FROM plugin_file"));
        }
        cases.addAll(serverInboxes());
        assertAll(cases.stream().map(DatabaseRoleIntegrationTest::check));
    }

    /**
     * What steward writes on its own clock.
     *
     * The curves, the payment poll and its cut-off, and a booking: the access it buys, appended by steward. Clearing
     * old requests out of every inbox is the owner's, so steward deletes from none of them.
     */
    private static List<Case> stewardsOwnLoops() {
        return List.of(
                may(
                        DatabaseRole.STEWARD,
                        "INSERT INTO metric_sample (subject, metric, resolution, at, value)"
                                + " SELECT 'host', 'x', 'RAW', now(), 1 WHERE false"),
                may(DatabaseRole.STEWARD, "DELETE FROM metric_sample WHERE false"),
                may(
                        DatabaseRole.STEWARD,
                        "INSERT INTO payment_gateway (state) SELECT 'ON' WHERE false"
                                + " ON CONFLICT (id) DO UPDATE SET state = excluded.state"),
                may(DatabaseRole.STEWARD, "UPDATE payment_request SET status = 'EXPIRED' WHERE false"),
                may(
                        DatabaseRole.STEWARD,
                        "INSERT INTO access_grant (discord_id, valid_from, valid_until, source)"
                                + " SELECT 'x', now(), now(), 'PURCHASE' WHERE false"),
                mayNot(DatabaseRole.STEWARD, "DELETE FROM access_grant WHERE false"),
                mayNot(DatabaseRole.STEWARD, "DELETE FROM smp_inbox WHERE false"),
                mayNot(DatabaseRole.STEWARD, "DELETE FROM bank_inbox WHERE false"));
    }

    /** The bot raises an alert and reads none back; steward routes them and keeps each admin's channels. */
    @Test
    void theBotRaisesAlertsAndOnlyStewardRoutesThem() {
        final String raise = "INSERT INTO admin_alert (raised_by, type, level, subject, title, detail, path)"
                + " VALUES ('x', 'BOT', 'WARN', 'a', 'b', '', '/')";
        assertAll(Stream.of(
                        may(DatabaseRole.DISCORD_BOT, raise),
                        mayNot(DatabaseRole.DISCORD_BOT, "SELECT count(*) FROM admin_alert"),
                        mayNot(DatabaseRole.SMP, raise),
                        may(DatabaseRole.STEWARD, "UPDATE admin_alert SET routed = now() WHERE false"),
                        may(DatabaseRole.STEWARD, "DELETE FROM steward_alert_preference WHERE false"),
                        mayNot(DatabaseRole.DISCORD_BOT, "SELECT count(*) FROM steward_alert_preference"))
                .map(DatabaseRoleIntegrationTest::check));
    }

    /** Every server claims only its own inbox, and steward asks every one of them for a reload. */
    private static List<Case> serverInboxes() {
        return List.of(
                mayNot(
                        DatabaseRole.SMP,
                        "INSERT INTO hunger_games_inbox (kind, payload, actor_kind) VALUES ('RELOAD', '{}', 'HOST')"),
                may(DatabaseRole.SMP, "UPDATE smp_inbox SET status = 'DONE' WHERE false"),
                mayNot(DatabaseRole.SMP, "UPDATE hunger_games_inbox SET status = 'DONE' WHERE false"),
                may(
                        DatabaseRole.STEWARD,
                        "INSERT INTO hunger_games_inbox (kind, payload, actor_kind) VALUES ('START_GAME', '{}', 'HOST')"),
                may(
                        DatabaseRole.STEWARD,
                        "INSERT INTO limbo_inbox (kind, payload, actor_kind) VALUES ('RELOAD', '{}', 'HOST')"),
                may(
                        DatabaseRole.STEWARD,
                        "INSERT INTO proxy_inbox (kind, payload, actor_kind) VALUES ('RELOAD', '{}', 'HOST')"),
                mayNot(DatabaseRole.STEWARD, "UPDATE limbo_inbox SET status = 'DONE' WHERE false"),
                mayNot(
                        DatabaseRole.PROXY,
                        "INSERT INTO smp_inbox (kind, payload, actor_kind) VALUES ('RELOAD', '{}', 'HOST')"),
                may(DatabaseRole.PROXY, "UPDATE proxy_inbox SET status = 'DONE' WHERE false"),
                mayNot(
                        DatabaseRole.LIMBO,
                        "INSERT INTO proxy_inbox (kind, payload, actor_kind) VALUES ('RELOAD', '{}', 'HOST')"));
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

    /** The bot names its status channels and the proxy its MOTD from one query over smp's and hunger-games' tables. */
    @Test
    void everyProcessThatNamesTheNetworkReadsTheSnapshotUnderItsOwnRole() {
        assertAll(List.of(DatabaseRole.DISCORD_BOT, DatabaseRole.PROXY).stream()
                .map(role -> (Executable) () -> assertDoesNotThrow(
                        () -> SnapshotDirectory.using(database.dataSourceAs(role))
                                .snapshot(),
                        role.name())));
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
