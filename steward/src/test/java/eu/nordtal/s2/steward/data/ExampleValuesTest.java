package eu.nordtal.s2.steward.data;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.s2.common.id.Actor;
import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.database.DatabaseRole;
import eu.nordtal.s2.database.RoundSeed;
import eu.nordtal.s2.database.TestDatabase;
import eu.nordtal.s2.database.setting.SettingStore;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The values an editor fills placeholders with: the admin's own rows, others', then a fixed value per type. */
class ExampleValuesTest {

    private static DataSource dataSource;

    /** The owner, for fixtures and for whatever stands in for another service. */
    private static DataSource owner;

    @BeforeAll
    static void start() {
        final TestDatabase database = TestDatabase.fresh();
        owner = database.dataSource();
        // The role steward logs in as, so a statement it was never granted fails here first.
        dataSource = database.dataSourceAs(DatabaseRole.STEWARD);
    }

    @BeforeEach
    void emptyTables() {
        sql("TRUNCATE account_link, discord_user, registration, hg_game, smp_milestone, setting_override CASCADE");
    }

    @Test
    void everyTypeHasAFallback() {
        final Map<String, Map<String, String>> examples = new ExampleValues(dataSource).of(DiscordId.of("1"), "Ada");

        assertEquals(Map.of("name", "Steve"), examples.get("player"));
        assertEquals(Map.of("name", "@Ada"), examples.get("discord-member"));
        assertEquals(Map.of("name", "Nordlichter"), examples.get("team"));
        assertEquals(Map.of("name", "smp"), examples.get("service"));
        assertEquals(Map.of("name", "frontier"), examples.get("milestone"));
        assertEquals(Map.of("number", "2", "name", "Season 2"), examples.get("season"));
    }

    @Test
    void theSeasonIsTheOneTheNetworksSettingsName() {
        final Map<String, @Nullable String> changes = new HashMap<>();
        changes.put("number", "3");
        changes.put("name", "\"Staffel Drei\"");
        SettingStore.using(dataSource).change(SettingStore.NETWORK, "season", changes, Actor.STEWARD, stored -> true);

        assertEquals(
                Map.of("number", "3", "name", "Staffel Drei"),
                new ExampleValues(dataSource).of(DiscordId.of("1"), "Ada").get("season"));
    }

    @Test
    void theAdminIsThePlayer() {
        sql("INSERT INTO discord_user (discord_id, discord_display_name) VALUES ('1', 'Ally H'), ('9', NULL)");
        sql("INSERT INTO account_link (discord_id, mc_uuid, mc_name) VALUES"
                + " ('9', '00000000-0000-0000-0000-000000000009', 'Other'),"
                + " ('1', '00000000-0000-0000-0000-000000000001', 'Ally')");

        final Map<String, Map<String, String>> examples = new ExampleValues(dataSource).of(DiscordId.of("1"), "Ada");
        assertEquals(Map.of("name", "Ally"), examples.get("player"));
        assertEquals(Map.of("name", "@Ally H"), examples.get("discord-member"));

        assertEquals(
                Map.of("name", "Other"),
                new ExampleValues(dataSource).of(DiscordId.of("5"), "Ada").get("player"),
                "an admin without a link gets a real player rather than the fallback");
    }

    @Test
    void realTeamAndActiveMilestone() {
        sql("INSERT INTO smp_milestone (key, state) VALUES ('foothold', 'UNLOCKED'), ('nether', 'ACTIVE')");
        final RoundSeed seed = new RoundSeed(owner);
        seed.team(seed.round("OPEN"), "Eisbären");

        final Map<String, Map<String, String>> examples = new ExampleValues(dataSource).of(DiscordId.of("1"), "Ada");
        assertEquals(Map.of("name", "Eisbären"), examples.get("team"));
        assertEquals(Map.of("name", "nether"), examples.get("milestone"));
    }

    private static void sql(final String statement) {
        try (var connection = owner.getConnection();
                var sql = connection.createStatement()) {
            sql.execute(statement);
        } catch (final SQLException failure) {
            throw new RuntimeException(failure);
        }
    }
}
