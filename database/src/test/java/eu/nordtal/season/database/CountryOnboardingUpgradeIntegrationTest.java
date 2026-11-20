package eu.nordtal.season.database;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;

class CountryOnboardingUpgradeIntegrationTest {
    @Test
    void theRunningBotKeepsItsOnboardingOverridesDuringTheUpgradeAndTheReplacedOnesGoLater() {
        final var database = TestDatabase.empty();
        final var placeholders = DatabaseRole.placeholders(DatabaseRole.PREFIX);
        database.migrate(placeholders, "126");
        final var jdbi = Jdbi.create(database.dataSource());
        final var paths =
                List.of("change-channel", "channel", "countries[0].name", "regions", "regions.name", "regions[0].name");
        jdbi.useHandle(handle -> {
            for (final var path : paths) {
                handle.execute(
                        "INSERT INTO setting_override (service, name, path, actor_kind, changed) VALUES ('discord-bot', 'onboarding', ?, 'HOST', now())",
                        path);
            }
            handle.execute(
                    "INSERT INTO discord_role (role_key, role_id) VALUES ('region/Europe/Berlin', '1'), ('country/de', '2')");
            handle.execute("""
                    INSERT INTO setting_override_node (service, name, path, node, parent, field, ordinal, kind, string, number, flag)
                    SELECT o.service, o.name, o.path, n.node, n.parent, n.field, n.ordinal, n.kind, n.string, n.number, n.flag
                    FROM setting_override o, setting_override_nodes('"old value"'::jsonb) n
                    """);
        });
        database.migrate(placeholders, "127");
        assertEquals(
                paths,
                jdbi.withHandle(handle -> handle.createQuery("SELECT path FROM setting_override ORDER BY path")
                        .mapTo(String.class)
                        .list()));
        assertEquals(
                paths,
                jdbi.withHandle(
                        handle -> handle.createQuery("SELECT DISTINCT path FROM setting_override_node ORDER BY path")
                                .mapTo(String.class)
                                .list()));
        database.migrate(placeholders, "140");
        assertEquals(
                List.of("channel", "countries[0].name"),
                jdbi.withHandle(
                        handle -> handle.createQuery("SELECT DISTINCT path FROM setting_override_node ORDER BY path")
                                .mapTo(String.class)
                                .list()));
        assertEquals(
                List.of("channel", "countries[0].name"),
                jdbi.withHandle(handle -> handle.createQuery("SELECT path FROM setting_override ORDER BY path")
                        .mapTo(String.class)
                        .list()));
        assertEquals(
                List.of("country/de"),
                jdbi.withHandle(handle -> handle.createQuery("SELECT role_key FROM discord_role ORDER BY role_key")
                        .mapTo(String.class)
                        .list()));
    }
}
