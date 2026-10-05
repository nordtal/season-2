package eu.nordtal.season.database;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;

/**
 * Holds what finding the bot's roles by name does to the role ids an installation configured before.
 *
 * Each one becomes the stored role of what it stood for, so the bot takes over the roles it already used.
 */
class DiscordRoleUpgradeIntegrationTest {

    @Test
    void everyConfiguredRoleIdBecomesTheStoredRoleOfWhatItStoodFor() {
        final TestDatabase upgraded = TestDatabase.empty();
        upgraded.migrate(DatabaseRole.placeholders(DatabaseRole.PREFIX), "28");
        final Jdbi jdbi = Jdbi.create(upgraded.dataSource());
        jdbi.useHandle(handle -> handle.execute("""
                INSERT INTO setting_override (service, name, path, value, actor_kind, changed) VALUES
                    ('discord-bot', 'access', 'roles.access', '"1544515346940301384"', 'HOST', now()),
                    ('discord-bot', 'access', 'roles.donor', '"1544515504889139301"', 'HOST', now()),
                    ('discord-bot', 'access', 'channels.admin', '"1397268057252036839"', 'HOST', now()),
                    ('discord-bot', 'access', 'languages', '[
                        {"tag": "en", "role": "1407097541761171496", "status-channel": "1"},
                        {"tag": "de", "role": "1407097130866311329"},
                        {"tag": "fr", "role": ""},
                        {"tag": "nl"}
                    ]', 'HOST', now()),
                    ('smp', 'access', 'roles.access', '"1"', 'HOST', now())
                """));

        upgraded.migrate(DatabaseRole.placeholders(DatabaseRole.PREFIX), "29");

        assertEquals(
                List.of(
                        "access=1544515346940301384",
                        "donor=1544515504889139301",
                        "language/de=1407097130866311329",
                        "language/en=1407097541761171496"),
                jdbi.withHandle(handle -> handle.createQuery(
                                "SELECT role_key || '=' || role_id FROM discord_role ORDER BY role_key")
                        .mapTo(String.class)
                        .list()),
                "a configured id is carried, an empty or missing one is not, and another service's rows are not read");
        final long overrides = jdbi.withHandle(handle -> handle.createQuery("SELECT count(*) FROM setting_override")
                .mapTo(Long.class)
                .one());
        assertEquals(5, overrides, "the old rows stay for the release that still reads them");
    }

    @Test
    void theRoleIdsTheAccessGroupHeldGoAndEveryOtherValueStays() {
        final TestDatabase upgraded = TestDatabase.empty();
        upgraded.migrate(DatabaseRole.placeholders(DatabaseRole.PREFIX), "32");
        final Jdbi jdbi = Jdbi.create(upgraded.dataSource());
        jdbi.useHandle(handle -> handle.execute("""
                INSERT INTO setting_override (service, name, path, value, actor_kind, changed) VALUES
                    ('discord-bot', 'access', 'roles.access', '"1544515346940301384"', 'HOST', now()),
                    ('discord-bot', 'access', 'roles.donor', '"1544515504889139301"', 'HOST', now()),
                    ('discord-bot', 'access', 'channels.admin', '"1397268057252036839"', 'HOST', now()),
                    ('discord-bot', 'access', 'languages', '[
                        {"tag": "en", "role": "1407097541761171496", "status-channel": "1"},
                        {"tag": "de", "role-name": "Deutsch"},
                        "not an entry"
                    ]', 'HOST', now()),
                    ('smp', 'access', 'roles.access', '"1"', 'HOST', now())
                """));

        upgraded.migrate(DatabaseRole.placeholders(DatabaseRole.PREFIX), "33");

        assertEquals(
                List.of(
                        "discord-bot/channels.admin=\"1397268057252036839\"",
                        "discord-bot/languages=[{\"tag\": \"en\", \"status-channel\": \"1\"},"
                                + " {\"tag\": \"de\", \"role-name\": \"Deutsch\"}, \"not an entry\"]",
                        "smp/roles.access=\"1\""),
                jdbi.withHandle(handle -> handle.createQuery(
                                "SELECT service || '/' || path || '=' || value::text FROM setting_override"
                                        + " ORDER BY service, path")
                        .mapTo(String.class)
                        .list()),
                "the access and donor ids and each language's role go, in order, and nothing else is touched");
    }
}
