package eu.nordtal.s2.database;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * Holds that a database migrated before steward's role was renamed ends up granting steward what a new one does.
 *
 * The installations of release 0.10 ran V1 and V2 with steward's role named {@code nordtal_steward_ui}; every later
 * migration grants to {@code nordtal_steward}, which those two never named.
 */
class StewardRoleUpgradeIntegrationTest {

    /** The name V1 and V2 granted steward's privileges to in the installations of release 0.10. */
    private static final String OLD_NAME = "nordtal_steward_ui";

    /** Every table and sequence privilege a role holds, and the roles it is a member of. */
    private static final String PRIVILEGES = """
            SELECT c.relname || ' ' || p.privilege
            FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = 'public'
            CROSS JOIN (VALUES ('SELECT'), ('INSERT'), ('UPDATE'), ('DELETE')) AS p (privilege)
            WHERE c.relkind = 'r' AND c.relname <> 'flyway_schema_history'
              AND has_table_privilege(?, c.oid, p.privilege)
            UNION ALL
            SELECT s.relname || ' USAGE'
            FROM (SELECT c.oid, c.relname
                  FROM pg_class c
                  JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = 'public'
                  WHERE c.relkind = 'S'
                  OFFSET 0) AS s
            -- The fence keeps the planner from asking a table whether it is a sequence.
            WHERE has_sequence_privilege(?, s.oid, 'USAGE')
            UNION ALL
            SELECT 'member of ' || r.rolname
            FROM pg_roles r
            WHERE r.rolname <> ? AND pg_has_role(?, r.oid, 'MEMBER')
            """;

    @Test
    void stewardHoldsWhatAFreshInstallationGrantsItAfterTheRename() throws SQLException {
        final TestDatabase upgraded = TestDatabase.empty();
        execute(upgraded.dataSource(), """
                DO $$
                BEGIN
                    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '%s') THEN
                        CREATE ROLE %s NOLOGIN;
                    END IF;
                END
                $$""".formatted(OLD_NAME, OLD_NAME));
        final Map<String, String> before = new java.util.HashMap<>(DatabaseRole.placeholders(DatabaseRole.PREFIX));
        before.put(DatabaseRole.STEWARD.placeholder(), OLD_NAME);
        upgraded.migrate(before, "2");
        upgraded.migrate(DatabaseRole.placeholders(DatabaseRole.PREFIX), "latest");

        final String steward = DatabaseRole.STEWARD.roleName();
        assertEquals(
                privileges(TestDatabase.fresh().dataSource(), steward),
                privileges(upgraded.dataSource(), steward),
                "steward lacks on a database migrated before the rename what it holds on a new one");
    }

    private static Set<String> privileges(final DataSource source, final String role) throws SQLException {
        final Set<String> held = new TreeSet<>();
        try (Connection connection = source.getConnection();
                var query = connection.prepareStatement(PRIVILEGES)) {
            for (int parameter = 1; parameter <= 4; parameter++) {
                query.setString(parameter, role);
            }
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    held.add(rows.getString(1));
                }
            }
        }
        return held;
    }

    private static void execute(final DataSource source, final String sql) throws SQLException {
        try (Connection connection = source.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
