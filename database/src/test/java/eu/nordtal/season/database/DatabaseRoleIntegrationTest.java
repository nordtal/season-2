package eu.nordtal.season.database;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.database.network.SnapshotDirectory;
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

    /** Only steward writes a setting; every other service reads its own. */
    private static final String WRITE_SETTING = "INSERT INTO setting_override (service, name, path, value, actor_kind,"
            + " changed) SELECT 'x', 'y', 'z', '1', 'HOST', now() WHERE false";

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
                // Every server reads a player's aura with the rest of who they are.
                may(DatabaseRole.LIMBO, "SELECT count(*) FROM smp_player"),
                may(DatabaseRole.HUNGER_GAMES, "SELECT count(*) FROM smp_player"),
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
            cases.add(may(role, "INSERT INTO audit_log (action, actor_kind) VALUES ('X', 'STEWARD')"));
            cases.add(mayNot(role, "UPDATE audit_log SET line = line WHERE false"));
            cases.add(mayNot(role, "DELETE FROM audit_log WHERE false"));
            cases.add(mayNot(role, "SELECT count(*) FROM service_plugin"));
            cases.add(mayNot(role, "SELECT count(*) FROM plugin_file"));
            cases.add(new Case(role, WRITE_SETTING, role == DatabaseRole.STEWARD));
        }
        cases.addAll(serverInboxes());
        cases.addAll(registration());
        cases.addAll(discordRoles());
        cases.addAll(commandTrees());
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
                + " VALUES ('x', 'BOT', 'WARN', 'a', '{\"key\": \"alert.dm\", \"args\": {}}', '[]', '/')";
        assertAll(Stream.of(
                        may(DatabaseRole.DISCORD_BOT, raise),
                        mayNot(DatabaseRole.DISCORD_BOT, "SELECT count(*) FROM admin_alert"),
                        mayNot(DatabaseRole.SMP, raise),
                        may(DatabaseRole.STEWARD, "UPDATE admin_alert SET routed = now() WHERE false"),
                        may(DatabaseRole.STEWARD, "DELETE FROM steward_alert_preference WHERE false"),
                        mayNot(DatabaseRole.DISCORD_BOT, "SELECT count(*) FROM steward_alert_preference"))
                .map(DatabaseRoleIntegrationTest::check));
    }

    /** The Discord roles the bot found are the bot's alone. */
    private static List<Case> discordRoles() {
        return List.of(
                may(
                        DatabaseRole.DISCORD_BOT,
                        "INSERT INTO discord_role (role_key, role_id) SELECT 'x', '1' WHERE false"),
                may(DatabaseRole.DISCORD_BOT, "DELETE FROM discord_role WHERE false"),
                mayNot(DatabaseRole.STEWARD, "SELECT count(*) FROM discord_role"),
                mayNot(DatabaseRole.SMP, "SELECT count(*) FROM discord_role"));
    }

    /** Each server writes its command tree with the store's own upsert and reads none back; steward only reads. */
    private static List<Case> commandTrees() {
        final String upsert = "INSERT INTO command_tree (server, tree, published) SELECT 'x', '{}', now() WHERE false"
                + " ON CONFLICT (server) DO UPDATE SET tree = '{}', published = now()";
        final List<Case> cases = new ArrayList<>();
        for (final DatabaseRole server :
                List.of(DatabaseRole.PROXY, DatabaseRole.LIMBO, DatabaseRole.HUNGER_GAMES, DatabaseRole.SMP)) {
            cases.add(may(server, upsert));
            cases.add(mayNot(server, "SELECT tree FROM command_tree"));
            cases.add(mayNot(server, "DELETE FROM command_tree WHERE false"));
        }
        cases.add(may(DatabaseRole.STEWARD, "SELECT server, tree, published FROM command_tree"));
        cases.add(mayNot(DatabaseRole.STEWARD, upsert));
        cases.add(mayNot(DatabaseRole.DISCORD_BOT, upsert));
        cases.add(mayNot(DatabaseRole.DISCORD_BOT, "SELECT tree FROM command_tree"));
        return cases;
    }

    /** The bot writes the registration and the game moves only its state; the game's own tables are the game's. */
    private static List<Case> registration() {
        return List.of(
                may(DatabaseRole.DISCORD_BOT, "INSERT INTO registration (game) SELECT 'hunger-games' WHERE false"),
                may(DatabaseRole.DISCORD_BOT, "UPDATE team_member SET state = 'ACCEPTED' WHERE false"),
                mayNot(
                        DatabaseRole.DISCORD_BOT,
                        "INSERT INTO hg_game (registration_id) SELECT gen_random_uuid() WHERE false"),
                mayNot(
                        DatabaseRole.DISCORD_BOT,
                        "INSERT INTO hg_ready (member_id) SELECT gen_random_uuid() WHERE false"),
                may(DatabaseRole.HUNGER_GAMES, "UPDATE registration SET state = 'CLOSED' WHERE false"),
                mayNot(DatabaseRole.HUNGER_GAMES, "UPDATE registration SET game = 'x' WHERE false"),
                mayNot(
                        DatabaseRole.HUNGER_GAMES,
                        "INSERT INTO team (registration_id, name) SELECT gen_random_uuid(), 'x' WHERE false"),
                mayNot(DatabaseRole.HUNGER_GAMES, "UPDATE team_member SET state = 'ACCEPTED' WHERE false"),
                may(
                        DatabaseRole.HUNGER_GAMES,
                        "INSERT INTO hg_game (registration_id) SELECT gen_random_uuid() WHERE false"),
                may(DatabaseRole.HUNGER_GAMES, "INSERT INTO hg_ready (member_id) SELECT gen_random_uuid() WHERE false"),
                may(DatabaseRole.HUNGER_GAMES, "UPDATE hg_team_colour SET colour_rgb = 0 WHERE false"),
                may(DatabaseRole.PROXY, "SELECT count(*) FROM team_member"),
                may(DatabaseRole.STEWARD, "SELECT count(*) FROM hg_ready"),
                mayNot(DatabaseRole.SMP, "SELECT count(*) FROM registration"));
    }

    /** Every server with an inbox claims only its own, and steward writes into each. */
    private static List<Case> serverInboxes() {
        return List.of(
                mayNot(
                        DatabaseRole.SMP,
                        "INSERT INTO hunger_games_inbox (kind, payload, actor_kind) VALUES ('START_GAME', '{}', 'HOST')"),
                may(DatabaseRole.SMP, "UPDATE smp_inbox SET status = 'DONE' WHERE false"),
                mayNot(DatabaseRole.SMP, "UPDATE hunger_games_inbox SET status = 'DONE' WHERE false"),
                may(DatabaseRole.HUNGER_GAMES, "UPDATE hunger_games_inbox SET status = 'DONE' WHERE false"),
                may(
                        DatabaseRole.STEWARD,
                        "INSERT INTO hunger_games_inbox (kind, payload, actor_kind) VALUES ('START_GAME', '{}', 'HOST')"),
                may(
                        DatabaseRole.STEWARD,
                        "INSERT INTO smp_inbox (kind, payload, actor_kind)"
                                + " VALUES ('UNLOCK_MILESTONE', '{\"key\":\"a\"}', 'HOST')"),
                mayNot(DatabaseRole.STEWARD, "UPDATE smp_inbox SET status = 'DONE' WHERE false"),
                mayNot(
                        DatabaseRole.PROXY,
                        "INSERT INTO smp_inbox (kind, payload, actor_kind) VALUES ('UNLOCK_MILESTONE', '{}', 'HOST')"),
                mayNot(
                        DatabaseRole.LIMBO,
                        "INSERT INTO hunger_games_inbox (kind, payload, actor_kind) VALUES ('START_GAME', '{}', 'HOST')"));
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
