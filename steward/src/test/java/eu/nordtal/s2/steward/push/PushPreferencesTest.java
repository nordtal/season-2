package eu.nordtal.s2.steward.push;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.database.TestDatabase;
import java.sql.SQLException;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@code steward_push_preference} against a real PostgreSQL, where a missing row is the type's default. */
class PushPreferencesTest {

    private static DataSource dataSource;

    private PushPreferences preferences;

    @BeforeAll
    static void start() {
        dataSource = TestDatabase.fresh().dataSource();
    }

    @BeforeEach
    void freshTable() {
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement()) {
            statement.execute("TRUNCATE steward_push_preference");
        } catch (final SQLException failure) {
            throw new RuntimeException(failure);
        }
        preferences = new PushPreferences(dataSource);
    }

    @Test
    void anAccountWithNoRowsGetsTheDefaults() {
        final Map<AlertType, Boolean> mine = preferences.of(DiscordId.of("42"));

        // Everything but drift is on.
        assertEquals(
                Map.of(
                        AlertType.SERVICE, true,
                        AlertType.BACKUP, true,
                        AlertType.DISK, true,
                        AlertType.MEMORY, true,
                        AlertType.DRIFT, false),
                mine,
                "the defaults an account gets before it has ever opened the dialog are not the" + " expected ones");
        assertTrue(
                preferences.all().isEmpty(),
                "reading the preferences of an account wrote a row for it - see V30 on why a"
                        + " missing row has to stay missing");
    }

    @Test
    void aSwitchIsScopedToTheAccount() {
        preferences.set(DiscordId.of("42"), AlertType.DRIFT, true);
        preferences.set(DiscordId.of("42"), AlertType.SERVICE, false);

        assertTrue(preferences.of(DiscordId.of("42")).get(AlertType.DRIFT));
        assertFalse(preferences.of(DiscordId.of("42")).get(AlertType.SERVICE));
        assertFalse(
                preferences.of(DiscordId.of("43")).get(AlertType.DRIFT), "one account's choice leaked into another's");
        assertTrue(
                preferences.of(DiscordId.of("43")).get(AlertType.SERVICE),
                "one account's choice leaked into another's");
    }

    @Test
    void settingTwiceIsOneRow() {
        preferences.set(DiscordId.of("42"), AlertType.DISK, false);
        preferences.set(DiscordId.of("42"), AlertType.DISK, true);

        assertEquals(1, preferences.all().get("42").size(), "the same switch set twice produced two rows");
        assertTrue(preferences.of(DiscordId.of("42")).get(AlertType.DISK));
    }

    @Test
    void choosingTheDefaultIsStillAChoice() {
        preferences.set(DiscordId.of("42"), AlertType.SERVICE, true);

        assertEquals(
                Map.of(AlertType.SERVICE, true),
                preferences.all().get("42"),
                "choosing a value that happens to equal the default deleted the row instead of" + " recording it");
    }

    @Test
    void anUnknownTypeIsIgnored() throws SQLException {
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement()) {
            statement.execute("INSERT INTO steward_push_preference (discord_id, alert_type,"
                    + " enabled, updated_at) VALUES ('42', 'sunspots', true, now())");
        }

        // A poll that threw on a row a later release left behind would stop pushing anything at all.
        assertEquals(5, preferences.of(DiscordId.of("42")).size());
        assertTrue(
                preferences.all().get("42") == null
                        || preferences.all().get("42").isEmpty(),
                "a type nobody knows was carried into the effective answer");
    }
}
