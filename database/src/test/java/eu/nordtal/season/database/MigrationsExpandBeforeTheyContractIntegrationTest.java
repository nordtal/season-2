package eu.nordtal.season.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import eu.nordtal.season.common.RepositoryRoot;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * Holds that no migration takes away what the proxy and limbo of the running release use.
 * Their standbys run the previous release during {@code migrate}.
 * A drop comes a release after the code stops using it.
 */
class MigrationsExpandBeforeTheyContractIntegrationTest {

    /** The services that run the previous release while the next one migrates. */
    private static final List<DatabaseRole> STANDING = List.of(DatabaseRole.PROXY, DatabaseRole.LIMBO);

    /** Each release and its newest migration. The release package adds a line when it bumps {@code version}. */
    private static final Map<String, String> NEWEST_MIGRATION = Map.ofEntries(
            Map.entry("0.12.1", "26"),
            Map.entry("0.13.0", "28"),
            Map.entry("0.13.1", "28"),
            Map.entry("0.13.2", "28"),
            Map.entry("0.14.0", "30"),
            Map.entry("0.15.0", "33"),
            Map.entry("0.16.0", "33"),
            Map.entry("0.16.1", "33"),
            Map.entry("0.17.0", "34"),
            Map.entry("0.18.0", "36"),
            Map.entry("0.18.1", "36"),
            Map.entry("0.18.2", "36"),
            Map.entry("0.18.3", "36"),
            Map.entry("0.18.4", "36"),
            Map.entry("0.18.5", "36"),
            Map.entry("0.19.0", "37"),
            Map.entry("0.20.0", "37"),
            Map.entry("0.21.0", "38"),
            Map.entry("0.22.0", "38"),
            Map.entry("0.23.0", "40"),
            Map.entry("0.24.0", "46"),
            Map.entry("0.25.0", "47"),
            Map.entry("0.26.0", "47"),
            Map.entry("0.27.0", "53"),
            Map.entry("0.28.0", "70"),
            Map.entry("0.28.1", "70"),
            Map.entry("0.29.0", "71"),
            Map.entry("0.30.0", "71"),
            Map.entry("0.31.0", "71"),
            Map.entry("0.32.0", "71"),
            Map.entry("0.33.0", "72"),
            Map.entry("0.34.0", "74"),
            Map.entry("0.35.0", "74"),
            Map.entry("0.36.0", "74"),
            Map.entry("0.37.0", "74"),
            Map.entry("0.38.0", "74"),
            Map.entry("0.39.0", "74"),
            Map.entry("0.40.0", "75"),
            Map.entry("0.41.0", "75"),
            Map.entry("0.42.0", "75"),
            Map.entry("0.43.0", "76"),
            Map.entry("0.44.0", "76"),
            Map.entry("0.45.0", "76"),
            Map.entry("0.46.0", "76"),
            Map.entry("0.47.0", "76"),
            Map.entry("0.48.0", "77"),
            Map.entry("0.49.0", "77"),
            Map.entry("0.50.0", "77"),
            Map.entry("0.51.0", "78"));

    /** The privileges a release no longer uses, with that release. A grant may go in the release after it. */
    private static final Map<String, String> NO_LONGER_USED = noLongerUsed();

    /** Every column and table privilege of a role, as {@code relation.column PRIVILEGE}. */
    private static final String PRIVILEGES = """
            SELECT c.relname || '.' || a.attname || ' ' || p.privilege
            FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = 'public'
            JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped
            CROSS JOIN (VALUES ('SELECT'), ('INSERT'), ('UPDATE')) AS p (privilege)
            WHERE c.relkind IN ('r', 'p', 'v', 'm') AND c.relname <> 'flyway_schema_history'
              AND has_column_privilege(?, c.oid, a.attnum, p.privilege)
            UNION ALL
            SELECT c.relname || ' DELETE'
            FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = 'public'
            WHERE c.relkind IN ('r', 'p') AND has_table_privilege(?, c.oid, 'DELETE')
            UNION ALL
            SELECT s.relname || ' USAGE'
            FROM (SELECT c.oid, c.relname
                  FROM pg_class c
                  JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = 'public'
                  WHERE c.relkind = 'S'
                  OFFSET 0) AS s
            -- The fence keeps the planner from asking a table whether it is a sequence.
            WHERE has_sequence_privilege(?, s.oid, 'USAGE')
            """;

    /** The columns an insert cannot leave out, as {@code relation.column}. */
    private static final String REQUIRED = """
            SELECT c.relname || '.' || a.attname
            FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = 'public'
            JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped
            WHERE c.relkind IN ('r', 'p') AND a.attnotnull AND NOT a.atthasdef
              AND a.attidentity = '' AND a.attgenerated = ''
            """;

    @Test
    void aMigrationKeepsEverythingTheRunningReleasesStandbysUse() throws SQLException, IOException {
        final String release = release();
        final String newest = NEWEST_MIGRATION.get(release);
        assertNotNull(
                newest,
                "release " + release + " names no newest migration: the release adds itself to NEWEST_MIGRATION");

        final TestDatabase database = TestDatabase.empty();
        final Map<String, String> placeholders = DatabaseRole.placeholders(DatabaseRole.PREFIX);
        database.migrate(placeholders, newest);
        final Map<DatabaseRole, Set<String>> before = new java.util.EnumMap<>(DatabaseRole.class);
        for (final DatabaseRole role : STANDING) {
            before.put(role, privileges(database.dataSource(), role));
        }
        final Set<String> requiredBefore = lines(database.dataSource(), REQUIRED);

        database.migrate(placeholders, "latest");

        final Set<String> lost = new TreeSet<>();
        final Set<String> newlyRequired = new TreeSet<>();
        final Set<String> requiredAfter = lines(database.dataSource(), REQUIRED);
        for (final DatabaseRole role : STANDING) {
            final Set<String> after = privileges(database.dataSource(), role);
            for (final String privilege : before.get(role)) {
                if (!after.contains(privilege) && !noLongerUsed(role, privilege, newest)) {
                    lost.add(role.roleName() + " " + privilege);
                }
                // An insert of the old code fails on a column it does not know, unless the column has a default.
                if (privilege.endsWith(" INSERT")) {
                    final String relation = privilege.substring(0, privilege.indexOf('.'));
                    for (final String column : requiredAfter) {
                        if (column.startsWith(relation + ".") && !requiredBefore.contains(column)) {
                            newlyRequired.add(role.roleName() + " " + column);
                        }
                    }
                }
            }
        }
        assertEquals(
                Set.of(),
                lost,
                "a migration took away what release " + release + "'s standbys use while migrate runs:"
                        + " drop it a release after the code stopped using it");
        assertEquals(
                Set.of(),
                newlyRequired,
                "a migration added a required column without a default to a table release " + release
                        + "'s standbys insert into");
    }

    private static Map<String, String> noLongerUsed() {
        final Map<String, String> unused = new HashMap<>();
        // The legacy team tables, which the proxy read before registration moved to the bot.
        listed(unused, "0.13.0", "nordtal_proxy", "SELECT", "hg_team", "id", "game_id", "name", "colour_rgb");
        listed(unused, "0.13.0", "nordtal_proxy", "SELECT", "hg_team", "colour_named", "created");
        listed(unused, "0.13.0", "nordtal_proxy", "SELECT", "hg_member", "id", "team_id", "game_id", "discord_id");
        listed(unused, "0.13.0", "nordtal_proxy", "SELECT", "hg_member", "state", "ready", "created");
        // The settings-file import, the only writer of setting_override besides steward.
        for (final String role : List.of("nordtal_proxy", "nordtal_limbo")) {
            listed(unused, "0.14.0", role, "INSERT", "setting_override", "service", "name", "path", "value");
            listed(unused, "0.14.0", role, "INSERT", "setting_override", "actor_kind", "actor_id", "changed");
        }
        // A tie's winner as text, which the proxy never read.
        listed(unused, "0.26.0", "nordtal_proxy", "SELECT", "hg_event", "detail");
        // The texts an override replaced, which only steward read.
        for (final String role : List.of("nordtal_proxy", "nordtal_limbo")) {
            listed(unused, "0.26.0", role, "SELECT", "message_override", "original");
        }
        // An override's hash on each variant row, which proxy and limbo now compute from the texts it replaced.
        for (final String role : List.of("nordtal_proxy", "nordtal_limbo")) {
            listed(unused, "0.50.0", role, "SELECT", "message_override", "replaced");
        }
        // The old journal, which no proxy or limbo code wrote.
        for (final String role : List.of("nordtal_proxy", "nordtal_limbo")) {
            listed(unused, "0.27.0", role, "INSERT", "audit_log", "id", "occurred", "action", "actor_kind", "actor_id");
            listed(unused, "0.27.0", role, "INSERT", "audit_log", "subject", "mc_uuid", "line", "reference", "days");
        }
        // The rebuilt Discord layout tables, which no proxy or limbo code read.
        for (final String role : List.of("nordtal_proxy", "nordtal_limbo")) {
            listed(unused, "0.33.0", role, "SELECT", "layout_line", "bundle", "key", "layout", "line", "list", "part");
            listed(unused, "0.33.0", role, "SELECT", "layout_line", "style");
            listed(unused, "0.33.0", role, "SELECT", "layout_line_text", "bundle", "language", "layout", "line");
            listed(unused, "0.33.0", role, "SELECT", "layout_line_text", "part", "text");
            listed(unused, "0.33.0", role, "SELECT", "layout_link", "bundle", "element", "layout", "url");
            listed(unused, "0.33.0", role, "SELECT", "layout_link_label", "bundle", "element", "label", "language");
            listed(unused, "0.33.0", role, "SELECT", "layout_link_label", "layout");
            listed(unused, "0.33.0", role, "SELECT", "layout_media", "address", "bundle", "image", "layout", "part");
            listed(unused, "0.33.0", role, "SELECT", "layout_media", "position");
            listed(unused, "0.33.0", role, "SELECT", "layout_override", "actor_id", "actor_kind", "bundle", "changed");
            listed(unused, "0.33.0", role, "SELECT", "layout_override", "layout", "replaced");
            listed(unused, "0.33.0", role, "SELECT", "layout_part", "accent", "bundle", "element", "layout", "parent");
            listed(unused, "0.33.0", role, "SELECT", "layout_part", "part", "type");
            listed(unused, "0.33.0", role, "SELECT", "layout_row_element", "bundle", "element", "layout", "part");
            listed(unused, "0.33.0", role, "SELECT", "layout_row_element", "position");
        }
        return Map.copyOf(unused);
    }

    private static void listed(
            final Map<String, String> unused,
            final String release,
            final String role,
            final String privilege,
            final String relation,
            final String... columns) {
        for (final String column : columns) {
            unused.put(role + " " + relation + "." + column + " " + privilege, release);
        }
    }

    /** Returns whether a release up to the baseline listed the privilege as unused. */
    private static boolean noLongerUsed(final DatabaseRole role, final String privilege, final String newest) {
        final String release = NO_LONGER_USED.get(role.roleName() + " " + privilege);
        if (release == null) {
            return false;
        }
        final String shipped = NEWEST_MIGRATION.get(release);
        return shipped != null && Integer.parseInt(shipped) <= Integer.parseInt(newest);
    }

    /** Returns the release that {@code gradle.properties} names. */
    private static String release() throws IOException {
        final Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(RepositoryRoot.resolve("gradle.properties"))) {
            properties.load(reader);
        }
        return properties.getProperty("version");
    }

    private static Set<String> privileges(final DataSource source, final DatabaseRole role) throws SQLException {
        final Set<String> held = new TreeSet<>();
        try (Connection connection = source.getConnection();
                PreparedStatement query = connection.prepareStatement(PRIVILEGES)) {
            for (int parameter = 1; parameter <= 3; parameter++) {
                query.setString(parameter, role.roleName());
            }
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    held.add(rows.getString(1));
                }
            }
        }
        return held;
    }

    private static Set<String> lines(final DataSource source, final String sql) throws SQLException {
        final Set<String> found = new TreeSet<>();
        try (Connection connection = source.getConnection();
                PreparedStatement query = connection.prepareStatement(sql);
                ResultSet rows = query.executeQuery()) {
            while (rows.next()) {
                found.add(rows.getString(1));
            }
        }
        return found;
    }
}
