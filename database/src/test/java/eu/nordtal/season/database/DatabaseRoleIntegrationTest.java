package eu.nordtal.season.database;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.database.network.SnapshotDirectory;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/** Holds what V1 grants each role: a needed statement goes through and an unneeded one is refused. */
class DatabaseRoleIntegrationTest {

    /** The SQLSTATE for "permission denied". */
    private static final String DENIED = "42501";

    private static final List<DatabaseRole> LOGINS = List.of(
            DatabaseRole.DISCORD_BOT,
            DatabaseRole.PROXY,
            DatabaseRole.LIMBO,
            DatabaseRole.HUNGER_GAMES,
            DatabaseRole.SMP,
            DatabaseRole.STEWARD);

    /** Only steward writes a setting. */
    private static final String WRITE_SETTING = "INSERT INTO setting_override_node (service, name, path, node, kind,"
            + " number) SELECT 'x', 'y', 'z', 0, 'NUMBER', 1 WHERE false";

    private static TestDatabase database;

    @BeforeAll
    static void freshDatabase() {
        database = TestDatabase.fresh();
    }

    private record Case(DatabaseRole role, String sql, boolean allowed) {}

    private static Case may(final DatabaseRole role, final String sql) {
        return new Case(role, sql, true);
    }

    /** Steward and the bot append to the journal and read it. Nobody changes or removes an entry. */
    private static List<Case> journal() {
        final List<Case> cases = new ArrayList<>();
        for (final DatabaseRole role : LOGINS) {
            final boolean journals = role == DatabaseRole.STEWARD || role == DatabaseRole.DISCORD_BOT;
            cases.add(new Case(
                    role, "INSERT INTO journal_entry (action, actor_kind) VALUES ('LINK', 'STEWARD')", journals));
            // The bot draws the card of an entry that steward wrote.
            cases.add(new Case(
                    role,
                    "SELECT e.id, b.days, r.kind, s.service, rr.archive, rp.artifact, p.reference FROM journal_entry e"
                            + " LEFT JOIN journal_booking b ON b.entry = e.id LEFT JOIN steward_inbox r ON r.id = e.run"
                            + " LEFT JOIN run_service s ON s.run = r.id LEFT JOIN steward_restore rr ON rr.request = r.id"
                            + " LEFT JOIN steward_remove_plugin rp ON rp.request = r.id"
                            + " LEFT JOIN payment_request p ON p.id = e.payment WHERE false",
                    journals));
            cases.add(mayNot(role, "UPDATE journal_entry SET subject = subject WHERE false"));
            cases.add(mayNot(role, "DELETE FROM journal_entry WHERE false"));
        }
        return cases;
    }

    private static Case mayNot(final DatabaseRole role, final String sql) {
        return new Case(role, sql, false);
    }

    @Test
    void everyRoleReachesWhatItOwnsAndReadsAndNothingElse() {
        final List<Case> cases = new ArrayList<>(List.of(
                may(DatabaseRole.PROXY, "UPDATE account_link SET mc_name = 'x' WHERE mc_uuid = gen_random_uuid()"),
                mayNot(DatabaseRole.PROXY, "UPDATE account_link SET discord_id = 'x' WHERE false"),
                // The proxy keeps the name of every login, and the bot reads it for the journal.
                may(
                        DatabaseRole.PROXY,
                        "INSERT INTO player_name (mc_uuid, mc_name, seen) SELECT gen_random_uuid(), 'x', now() WHERE false"),
                may(DatabaseRole.PROXY, "UPDATE player_name SET mc_name = 'x' WHERE false"),
                may(DatabaseRole.DISCORD_BOT, "SELECT count(*) FROM player_name"),
                mayNot(DatabaseRole.DISCORD_BOT, "UPDATE player_name SET mc_name = 'x' WHERE false"),
                mayNot(
                        DatabaseRole.PROXY,
                        "INSERT INTO steward_session (id, csrf, created_at, expires_at) VALUES ('x', 'y', now(), now())"),
                may(DatabaseRole.LIMBO, "SELECT count(*) FROM discord_user"),
                // Every server reads a player's aura with the rest of who they are.
                may(DatabaseRole.LIMBO, "SELECT count(*) FROM smp_player"),
                may(DatabaseRole.HUNGER_GAMES, "SELECT count(*) FROM smp_player"),
                mayNot(DatabaseRole.LIMBO, "INSERT INTO discord_user (discord_id) VALUES ('1')"),
                may(DatabaseRole.SMP, "UPDATE smp_player SET aura = 0 WHERE false"),
                mayNot(DatabaseRole.SMP, "INSERT INTO hg_event (game_id, type) VALUES (gen_random_uuid(), 'x')"),
                mayNot(DatabaseRole.SMP, "SELECT count(*) FROM hg_game"),
                may(DatabaseRole.HUNGER_GAMES, "INSERT INTO hg_event (game_id, type) VALUES (gen_random_uuid(), 'x')"),
                mayNot(DatabaseRole.HUNGER_GAMES, "UPDATE smp_player SET aura = 0 WHERE false"),
                may(DatabaseRole.STEWARD, "INSERT INTO steward_inbox (kind, actor_kind) VALUES ('UPDATE', 'HOST')"),
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
                may(DatabaseRole.SMP, "INSERT INTO bot_inbox (kind, actor_kind) VALUES ('ANNOUNCE', 'STEWARD')"),
                mayNot(DatabaseRole.DISCORD_BOT, "SELECT count(*) FROM steward_session")));
        cases.addAll(stewardsOwnLoops());
        for (final DatabaseRole role : LOGINS) {
            cases.add(mayNot(role, "SELECT count(*) FROM service_plugin"));
            cases.add(mayNot(role, "SELECT count(*) FROM plugin_file"));
            cases.add(new Case(role, WRITE_SETTING, role == DatabaseRole.STEWARD));
        }
        cases.addAll(journal());
        cases.addAll(serverInboxes());
        cases.addAll(npcs());
        cases.addAll(bankInbox());
        cases.addAll(registration());
        cases.addAll(discordRoles());
        cases.addAll(commandTrees());
        cases.addAll(prism());
        assertAll(cases.stream().map(DatabaseRoleIntegrationTest::check));
    }

    /** Prism runs inside smp and makes its own tables and functions in its schema, and nowhere else. */
    private static List<Case> prism() {
        final List<Case> cases = new ArrayList<>(List.of(
                may(DatabaseRole.SMP, "CREATE TABLE prism.prism_probe (id int)"),
                may(DatabaseRole.SMP, "CREATE FUNCTION prism.prism_probe() RETURNS int LANGUAGE sql AS 'SELECT 1'"),
                mayNot(DatabaseRole.SMP, "CREATE TABLE public.prism_probe (id int)")));
        for (final DatabaseRole role : LOGINS) {
            if (role != DatabaseRole.SMP) {
                cases.add(mayNot(role, "CREATE TABLE prism.prism_probe (id int)"));
            }
        }
        return cases;
    }

    /** What steward writes on its own clock: the curves, the payment poll and bookings. It deletes from no inbox. */
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

    /** The bot raises an alert and reads it back for its card. Only steward routes them. */
    @Test
    void theBotRaisesAlertsAndOnlyStewardRoutesThem() {
        final String measure = "INSERT INTO alert_measure (entry, position, gauge, subject)"
                + " SELECT id, 0, 'DISK_PERCENT', 'disk' FROM journal_entry WHERE false";
        final String route = "UPDATE journal_entry SET routed = now() WHERE false";
        assertAll(Stream.of(
                        may(DatabaseRole.DISCORD_BOT, measure),
                        may(DatabaseRole.DISCORD_BOT, "SELECT count(*) FROM alert_measure"),
                        mayNot(DatabaseRole.SMP, measure),
                        may(DatabaseRole.STEWARD, route),
                        mayNot(DatabaseRole.DISCORD_BOT, route),
                        mayNot(DatabaseRole.STEWARD, "UPDATE journal_entry SET level = 'OK' WHERE false"),
                        may(DatabaseRole.STEWARD, "DELETE FROM steward_alert_preference WHERE false"),
                        mayNot(DatabaseRole.DISCORD_BOT, "SELECT count(*) FROM steward_alert_preference"))
                .map(DatabaseRoleIntegrationTest::check));
    }

    /** The Discord roles the bot found belong to the bot. */
    private static List<Case> discordRoles() {
        return List.of(
                may(
                        DatabaseRole.DISCORD_BOT,
                        "INSERT INTO discord_role (role_key, role_id) SELECT 'x', '1' WHERE false"),
                may(DatabaseRole.DISCORD_BOT, "DELETE FROM discord_role WHERE false"),
                mayNot(DatabaseRole.STEWARD, "SELECT count(*) FROM discord_role"),
                mayNot(DatabaseRole.SMP, "SELECT count(*) FROM discord_role"));
    }

    /** The bot writes the guild channels. Steward only reads them. */
    @Test
    void theBotPublishesTheGuildsChannelsAndOnlyStewardReadsThem() {
        assertAll(guildChannels().stream().map(DatabaseRoleIntegrationTest::check));
    }

    private static List<Case> guildChannels() {
        return List.of(
                may(
                        DatabaseRole.DISCORD_BOT,
                        "INSERT INTO guild_channels (guild_id, channels, published) SELECT '1', '[]', now() WHERE false"
                                + " ON CONFLICT (guild_id) DO UPDATE SET channels = '[]', published = now()"),
                mayNot(DatabaseRole.DISCORD_BOT, "SELECT channels FROM guild_channels"),
                mayNot(DatabaseRole.DISCORD_BOT, "DELETE FROM guild_channels WHERE false"),
                may(DatabaseRole.STEWARD, "SELECT channels FROM guild_channels"),
                mayNot(DatabaseRole.STEWARD, "UPDATE guild_channels SET channels = '[]' WHERE false"),
                mayNot(DatabaseRole.SMP, "SELECT channels FROM guild_channels"));
    }

    /** Each server writes its command tree and reads none. Steward only reads. */
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

    /** The bot writes the registration and the game moves only its state. */
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

    /** The bot and steward ask the inbox of the bank. Only steward claims. */
    private static List<Case> bankInbox() {
        return List.of(
                may(
                        DatabaseRole.DISCORD_BOT,
                        "INSERT INTO bank_inbox (kind, actor_kind) VALUES ('CANCEL_TAB', 'HOST')"),
                mayNot(DatabaseRole.DISCORD_BOT, "UPDATE bank_inbox SET status = 'DONE' WHERE false"),
                may(DatabaseRole.STEWARD, "INSERT INTO bank_inbox (kind, actor_kind) VALUES ('CANCEL_TAB', 'HOST')"),
                may(DatabaseRole.STEWARD, "UPDATE bank_inbox SET status = 'DONE' WHERE false"),
                may(
                        DatabaseRole.DISCORD_BOT,
                        "INSERT INTO bank_open_tab (request, payment) VALUES (0, gen_random_uuid())"),
                may(
                        DatabaseRole.STEWARD,
                        "INSERT INTO bank_cancel_tab (request, payment) VALUES (0, gen_random_uuid())"),
                mayNot(DatabaseRole.SMP, "INSERT INTO bank_open_tab (request, payment) VALUES (0, gen_random_uuid())"));
    }

    /** Steward places NPCs and smp spawns them. smp alone remembers who skips a dialogue. */
    private static List<Case> npcs() {
        return List.of(
                may(DatabaseRole.STEWARD, "UPDATE npc SET pitch = 1 WHERE false"),
                may(DatabaseRole.STEWARD, "DELETE FROM npc_equipment WHERE false"),
                may(DatabaseRole.STEWARD, "SELECT count(*) FROM npc_dialogue_version"),
                may(
                        DatabaseRole.STEWARD,
                        "INSERT INTO npc_skin (id, model, png, actor_kind, created) "
                                + "VALUES ('00000000-0000-0000-0000-000000000001', 'CLASSIC', '\\x00', 'STEWARD', now())"),
                may(
                        DatabaseRole.STEWARD,
                        "INSERT INTO npc_artwork (id, png, actor_kind, created) "
                                + "VALUES ('00000000-0000-0000-0000-000000000001', '\\x00', 'STEWARD', now())"),
                mayNot(DatabaseRole.STEWARD, "DELETE FROM npc_artwork WHERE false"),
                mayNot(DatabaseRole.STEWARD, "UPDATE npc_artwork SET png = png WHERE false"),
                may(DatabaseRole.STEWARD, "SELECT count(*) FROM npc_menu"),
                mayNot(DatabaseRole.STEWARD, "DELETE FROM npc_menu WHERE false"),
                mayNot(DatabaseRole.STEWARD, "UPDATE npc_dialogue_version SET saved = now() WHERE false"),
                mayNot(DatabaseRole.STEWARD, "DELETE FROM npc_skip WHERE false"),
                may(DatabaseRole.SMP, "SELECT count(*) FROM npc"),
                may(DatabaseRole.SMP, "SELECT count(*) FROM npc_skin"),
                may(DatabaseRole.SMP, "SELECT npcs, artworks FROM pack_build WHERE false"),
                may(DatabaseRole.SMP, "SELECT count(*) FROM npc_menu"),
                may(DatabaseRole.SMP, "DELETE FROM npc_menu WHERE false"),
                may(DatabaseRole.SMP, "DELETE FROM npc_skip WHERE false"),
                mayNot(DatabaseRole.SMP, "UPDATE npc SET pitch = 1 WHERE false"),
                may(DatabaseRole.SMP, "SELECT count(*) FROM npc_artwork"),
                mayNot(DatabaseRole.SMP, "SELECT count(*) FROM npc_dialogue_version"));
    }

    /** Every server with an inbox claims its own. Steward writes into each. */
    private static List<Case> serverInboxes() {
        return List.of(
                mayNot(
                        DatabaseRole.SMP,
                        "INSERT INTO hunger_games_inbox (kind, actor_kind) VALUES ('START_GAME', 'HOST')"),
                may(DatabaseRole.SMP, "UPDATE smp_inbox SET status = 'DONE' WHERE false"),
                mayNot(DatabaseRole.SMP, "UPDATE hunger_games_inbox SET status = 'DONE' WHERE false"),
                may(DatabaseRole.HUNGER_GAMES, "UPDATE hunger_games_inbox SET status = 'DONE' WHERE false"),
                may(
                        DatabaseRole.STEWARD,
                        "INSERT INTO hunger_games_inbox (kind, actor_kind) VALUES ('START_GAME', 'HOST')"),
                may(
                        DatabaseRole.STEWARD,
                        "INSERT INTO smp_inbox (kind, actor_kind) VALUES ('UNLOCK_MILESTONE', 'HOST')"),
                may(DatabaseRole.STEWARD, "INSERT INTO smp_unlock_milestone (request, key) VALUES (0, 'a')"),
                may(DatabaseRole.STEWARD, "INSERT INTO smp_complete_objective (request, key) VALUES (0, 'a')"),
                may(DatabaseRole.STEWARD, "INSERT INTO hunger_games_start_game (request, confirmed) VALUES (0, true)"),
                may(DatabaseRole.SMP, "SELECT count(*) FROM smp_unlock_milestone"),
                mayNot(DatabaseRole.SMP, "INSERT INTO smp_unlock_milestone (request, key) VALUES (0, 'a')"),
                may(DatabaseRole.HUNGER_GAMES, "SELECT count(*) FROM hunger_games_start_game"),
                // Steward withdraws its own take-in, so it moves an smp row's status and nothing else.
                may(DatabaseRole.STEWARD, "UPDATE smp_inbox SET status = 'CANCELLED', finished = now() WHERE false"),
                mayNot(DatabaseRole.STEWARD, "UPDATE smp_inbox SET kind = 'SET_AURA' WHERE false"),
                // It withdraws a question it asked hunger-games the same way, such as a timed-out settings trial.
                may(
                        DatabaseRole.STEWARD,
                        "UPDATE hunger_games_inbox SET status = 'CANCELLED', finished = now() WHERE false"),
                mayNot(DatabaseRole.STEWARD, "UPDATE hunger_games_inbox SET kind = 'START_GAME' WHERE false"),
                mayNot(
                        DatabaseRole.PROXY,
                        "INSERT INTO smp_inbox (kind, actor_kind) VALUES ('UNLOCK_MILESTONE', 'HOST')"),
                mayNot(
                        DatabaseRole.LIMBO,
                        "INSERT INTO hunger_games_inbox (kind, actor_kind) VALUES ('START_GAME', 'HOST')"));
    }

    private static Executable check(final Case c) {
        return () -> assertEquals(
                c.allowed(),
                allowed(c.role(), c.sql()),
                c.role().roleName() + (c.allowed() ? " may: " : " may not: ") + c.sql());
    }

    /** Runs the statement, rolls it back and counts any failure but a refused permission as allowed. */
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

    /** The bot names its status channels and the proxy its MOTD from one query. */
    @Test
    void everyProcessThatNamesTheNetworkReadsTheSnapshotUnderItsOwnRole() {
        assertAll(List.of(DatabaseRole.DISCORD_BOT, DatabaseRole.PROXY).stream()
                .map(role -> (Executable) () -> assertDoesNotThrow(
                        () -> SnapshotDirectory.using(database.dataSourceAs(role))
                                .snapshot(),
                        role.name())));
    }

    /** The service each login publishes its setting groups under, beside the network's. */
    private static final Map<DatabaseRole, String> SETTING_OWNERS = Map.of(
            DatabaseRole.DISCORD_BOT, "discord-bot",
            DatabaseRole.PROXY, "proxy",
            DatabaseRole.LIMBO, "limbo",
            DatabaseRole.HUNGER_GAMES, "hunger-games",
            DatabaseRole.SMP, "smp",
            DatabaseRole.STEWARD, "steward");

    /** A leaked password of one service changes no setting group of another, while each still publishes its own. */
    @Test
    void aServiceWritesItsOwnSettingGroupsAndTheNetworksAndNoOther() throws SQLException {
        final List<String> services = new ArrayList<>(SETTING_OWNERS.values());
        services.add("network");
        services.add("steward-agent");
        try (Connection owner = database.dataSource().getConnection();
                Statement statement = owner.createStatement()) {
            for (final String service : services) {
                statement.execute(publish(service, "probe-held"));
            }
        }
        final List<Executable> checks = new ArrayList<>();
        for (final Map.Entry<DatabaseRole, String> login : SETTING_OWNERS.entrySet()) {
            for (final String service : services) {
                final boolean own = service.equals(login.getValue()) || service.equals("network");
                final DatabaseRole role = login.getKey();
                checks.add(() -> assertEquals(
                        own, allowed(role, publish(service, "probe")), role.roleName() + " publishes " + service));
                checks.add(() -> assertEquals(
                        own ? 1 : 0,
                        updated(
                                role,
                                "UPDATE setting_group SET problem = 'x' WHERE service = '" + service + "'"
                                        + " AND name = 'probe-held'"),
                        role.roleName() + " marks a problem of " + service));
            }
        }
        assertAll(checks);
    }

    private static String publish(final String service, final String name) {
        return "INSERT INTO setting_group (service, name, schema, defaults, environment, live, published)"
                + " VALUES ('" + service + "', '" + name + "', '{}', '{}', '{}', false, now())"
                + " ON CONFLICT (service, name) DO UPDATE SET published = excluded.published";
    }

    /** Runs the statement, rolls it back and returns how many rows it changed. */
    private static int updated(final DatabaseRole role, final String sql) throws SQLException {
        try (Connection connection = database.dataSourceAs(role).getConnection()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                return statement.executeUpdate(sql);
            } finally {
                connection.rollback();
            }
        }
    }

    /** pg_dump reads every table with row security off, which fails for a role that a policy would filter. */
    @Test
    void theBackupRoleReadsPastEveryRowPolicy() {
        assertEquals(
                "",
                database.socketAs(DatabaseRole.BACKUP, "SET row_security = off; SELECT count(*) FROM setting_group"));
    }

    /** A cluster restored from a dump holds no role attribute, so provisioning alone lets pg_dump pass the policies. */
    @Test
    void provisioningLetsTheBackupRoleAloneBypassRowSecurity() throws SQLException {
        final String prefix = "provisioned_" + UUID.randomUUID().toString().substring(0, 8) + "_";
        final Map<DatabaseRole, String> passwords = new EnumMap<>(DatabaseRole.class);
        for (final DatabaseRole role : DatabaseRole.values()) {
            if (role.hasPassword()) {
                passwords.put(role, role.key());
            }
        }
        try (Connection owner = database.dataSource().getConnection();
                Statement statement = owner.createStatement()) {
            try {
                DatabaseRole.provision(database.dataSource(), prefix, passwords);
                final List<String> bypassing = new ArrayList<>();
                try (ResultSet row = statement.executeQuery(
                        "SELECT rolname FROM pg_roles WHERE rolbypassrls AND rolname LIKE '" + prefix + "%'")) {
                    while (row.next()) {
                        bypassing.add(row.getString(1));
                    }
                }
                assertEquals(List.of(DatabaseRole.BACKUP.roleName(prefix)), bypassing);
            } finally {
                for (final DatabaseRole role : DatabaseRole.values()) {
                    statement.execute("DROP ROLE IF EXISTS " + role.roleName(prefix));
                }
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
