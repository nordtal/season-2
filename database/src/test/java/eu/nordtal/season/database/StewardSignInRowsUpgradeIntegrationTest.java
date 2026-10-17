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

/** Holds that a key's transports and a browser's device name, once composed text, arrive as one value per column. */
class StewardSignInRowsUpgradeIntegrationTest {

    @Test
    void transportsBecomeRowsAndADeviceNameItsParts() throws SQLException {
        final TestDatabase upgraded = TestDatabase.empty();
        upgraded.migrate(DatabaseRole.placeholders(DatabaseRole.PREFIX), "47");
        execute(upgraded.dataSource(), """
                INSERT INTO steward_credential (credential_id, discord_id, public_key, label, transports, created_at)
                VALUES ('\\x01', '1', '\\x00', 'usb and nfc', 'usb,nfc', now()),
                       ('\\x02', '1', '\\x00', 'none said', NULL, now());
                INSERT INTO steward_push_subscription (endpoint, discord_id, p256dh, auth, created_at, device)
                VALUES ('https://push.example/both', '1', 'p', 'a', now(), 'iPhone, Safari'),
                       ('https://push.example/system', '1', 'p', 'a', now(), 'Linux'),
                       ('https://push.example/browser', '1', 'p', 'a', now(), 'Firefox'),
                       ('https://push.example/none', '1', 'p', 'a', now(), NULL);
                """);

        upgraded.migrate(DatabaseRole.placeholders(DatabaseRole.PREFIX), "latest");

        assertEquals(
                List.of("01 nfc", "01 usb"),
                rows(
                        upgraded.dataSource(),
                        "SELECT encode(credential_id, 'hex') || ' ' || transport"
                                + " FROM steward_credential_transport ORDER BY 1"));
        assertEquals(
                List.of(
                        "https://push.example/both iPhone Safari",
                        "https://push.example/browser - Firefox",
                        "https://push.example/none - -",
                        "https://push.example/system Linux -"),
                rows(
                        upgraded.dataSource(),
                        "SELECT endpoint || ' ' || coalesce(system, '-') || ' '"
                                + " || coalesce(browser, '-') FROM steward_push_subscription ORDER BY 1"));
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
