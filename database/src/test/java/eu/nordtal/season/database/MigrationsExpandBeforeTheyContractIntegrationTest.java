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
 * Holds that no migration takes away what the running release's proxy and limbo use.
 *
 * Their standbys keep running the previous release while {@code migrate} runs, so a drop comes a release after the
 * code stopped using it. What a role is granted stands for what its code uses.
 */
class MigrationsExpandBeforeTheyContractIntegrationTest {

    /** The services that keep running the previous release while the next one migrates. */
    private static final List<DatabaseRole> STANDING = List.of(DatabaseRole.PROXY, DatabaseRole.LIMBO);

    /**
     * Each release and the newest migration it ships, so a test on that release knows what its standbys use.
     *
     * The release package adds its own line when it bumps {@code version}.
     */
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
            Map.entry("0.18.2", "36"));

    /**
     * What a release's own code no longer uses, keyed as a privilege is listed, with that release.
     *
     * A grant listed here may go in the release after the one named, and the line goes with the grant.
     */
    private static final Map<String, String> NO_LONGER_USED = noLongerUsed();

    /** Every column and table privilege a role holds, one line each, as {@code relation.column PRIVILEGE}. */
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
                // An insert the old code writes fails on a column it cannot know about, unless it has a default.
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
        // The legacy team tables, which the proxy's snapshot read before registration was the bot's.
        listed(unused, "0.13.0", "nordtal_proxy", "SELECT", "hg_team", "id", "game_id", "name", "colour_rgb");
        listed(unused, "0.13.0", "nordtal_proxy", "SELECT", "hg_team", "colour_named", "created");
        listed(unused, "0.13.0", "nordtal_proxy", "SELECT", "hg_member", "id", "team_id", "game_id", "discord_id");
        listed(unused, "0.13.0", "nordtal_proxy", "SELECT", "hg_member", "state", "ready", "created");
        // The settings-file import, the only writer of setting_override besides steward.
        for (final String role : List.of("nordtal_proxy", "nordtal_limbo")) {
            listed(unused, "0.14.0", role, "INSERT", "setting_override", "service", "name", "path", "value");
            listed(unused, "0.14.0", role, "INSERT", "setting_override", "actor_kind", "actor_id", "changed");
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

    /** Returns whether a release up to the baseline listed the privilege as no longer used. */
    private static boolean noLongerUsed(final DatabaseRole role, final String privilege, final String newest) {
        final String release = NO_LONGER_USED.get(role.roleName() + " " + privilege);
        if (release == null) {
            return false;
        }
        final String shipped = NEWEST_MIGRATION.get(release);
        return shipped != null && Integer.parseInt(shipped) <= Integer.parseInt(newest);
    }

    /** Returns the release {@code gradle.properties} names, which is the one installed while this tree is built. */
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
