package eu.nordtal.season.steward.alert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.database.DatabaseRole;
import eu.nordtal.season.database.TestDatabase;
import eu.nordtal.season.database.alert.AlertChannel;
import eu.nordtal.season.database.alert.AlertType;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@code steward_alert_preference}, where a missing row is the type's default on that channel. */
class AlertPreferencesTest {

    private static final DiscordId ADMIN = DiscordId.of("42");

    private static DataSource dataSource;

    /** The owner, for fixtures and for whatever stands in for another service. */
    private static DataSource owner;

    private AlertPreferences preferences;

    @BeforeAll
    static void startDatabase() {
        final TestDatabase database = TestDatabase.fresh();
        owner = database.dataSource();
        // The role steward logs in as, so a statement it was never granted fails here first.
        dataSource = database.dataSourceAs(DatabaseRole.STEWARD);
    }

    @BeforeEach
    void freshTable() throws java.sql.SQLException {
        try (var connection = owner.getConnection();
                var statement = connection.createStatement()) {
            statement.execute("TRUNCATE steward_alert_preference");
        }
        preferences = new AlertPreferences(dataSource);
    }

    @Test
    void anAdminWhoNeverChoseGetsTheDefaults() {
        final Map<AlertType, Map<AlertChannel, Boolean>> mine = preferences.of(ADMIN);
        assertEquals(AlertType.values().length, mine.size());
        assertEquals(Map.of(AlertChannel.PUSH, true, AlertChannel.DISCORD, false), mine.get(AlertType.SERVICE));
        assertEquals(Map.of(AlertChannel.PUSH, false, AlertChannel.DISCORD, false), mine.get(AlertType.DRIFT));
        assertEquals(Map.of(AlertChannel.PUSH, true, AlertChannel.DISCORD, true), mine.get(AlertType.RUN));
        assertEquals(Map.of(AlertChannel.PUSH, false, AlertChannel.DISCORD, true), mine.get(AlertType.BOT));
    }

    @Test
    void aSwitchChangesOneChannelOfOneTypeForOneAdmin() {
        preferences.set(ADMIN, AlertType.SERVICE, AlertChannel.DISCORD, true);
        preferences.set(ADMIN, AlertType.SERVICE, AlertChannel.DISCORD, true);
        preferences.set(ADMIN, AlertType.SERVICE, AlertChannel.PUSH, false);

        assertEquals(
                Map.of(AlertChannel.PUSH, false, AlertChannel.DISCORD, true),
                preferences.of(ADMIN).get(AlertType.SERVICE));
        final AlertPreferences.Chosen all = preferences.all();
        assertTrue(all.wants(ADMIN, AlertType.SERVICE, AlertChannel.DISCORD));
        assertFalse(all.wants(DiscordId.of("43"), AlertType.SERVICE, AlertChannel.DISCORD));
    }

    @Test
    void aRowThisBuildDoesNotKnowIsIgnored() throws java.sql.SQLException {
        try (var connection = owner.getConnection();
                var statement = connection.createStatement()) {
            statement.execute("INSERT INTO steward_alert_preference (discord_id, alert_type, channel, enabled,"
                    + " updated_at) VALUES ('42', 'sunspots', 'PUSH', false, now())");
        }
        // A routing round that threw on a row a later release left behind would route nothing at all.
        assertTrue(preferences.all().wants(ADMIN, AlertType.SERVICE, AlertChannel.PUSH));
        assertEquals(AlertType.values().length, preferences.of(ADMIN).size());
    }
}
