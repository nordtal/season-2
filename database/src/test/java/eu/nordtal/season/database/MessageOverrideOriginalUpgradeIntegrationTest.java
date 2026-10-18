package eu.nordtal.season.database;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** Holds that texts an override replaced, once an array on every variant row, arrive as one row each. */
class MessageOverrideOriginalUpgradeIntegrationTest {

    @Test
    void theReplacedTextsBecomeARowPerVariantStoredOnce() throws SQLException {
        final TestDatabase upgraded = TestDatabase.empty();
        upgraded.migrate(DatabaseRole.placeholders(DatabaseRole.PREFIX), "47");
        execute(upgraded.dataSource(), """
                INSERT INTO message_override
                    (bundle, key, language, variant, text, replaced, original, actor_kind, changed)
                VALUES ('smp', 'tab.footer', 'de', 0, 'Moin', repeat('a', 64), '["Hallo", "Hi {name}"]', 'HOST', now()),
                       ('smp', 'tab.footer', 'de', 1, 'Servus', repeat('a', 64), '["Hallo", "Hi {name}"]', 'HOST',
                        now()),
                       ('proxy', 'motd', 'de', 0, 'Moin', NULL, NULL, 'HOST', now());
                """);

        upgraded.migrate(DatabaseRole.placeholders(DatabaseRole.PREFIX), "latest");

        assertEquals(
                List.of("smp tab.footer de 0 Hallo", "smp tab.footer de 1 Hi {name}"),
                rows(
                        upgraded.dataSource(),
                        "SELECT concat_ws(' ', bundle, key, language, variant, text) FROM message_override_original"
                                + " ORDER BY 1"));
    }

    private static List<String> rows(final DataSource source, final String sql) throws SQLException {
        final List<String> found = new ArrayList<>();
        try (Connection connection = source.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                found.add(rows.getString(1));
            }
        }
        return found;
    }

    private static void execute(final DataSource source, final String sql) throws SQLException {
        try (Connection connection = source.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
