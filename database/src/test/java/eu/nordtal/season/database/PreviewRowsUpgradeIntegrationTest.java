package eu.nordtal.season.database;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;

/** Holds that a text preview written as JSON goes, and every other request of the three inboxes stays. */
class PreviewRowsUpgradeIntegrationTest {

    @Test
    void aTextPreviewWrittenAsJsonGoesAndTheOtherRequestsStay() {
        final TestDatabase upgraded = TestDatabase.empty();
        upgraded.migrate(DatabaseRole.placeholders(DatabaseRole.PREFIX), "87");
        final Jdbi jdbi = Jdbi.create(upgraded.dataSource());
        jdbi.useHandle(handle -> handle.execute("""
                INSERT INTO bot_inbox (kind, payload, actor_kind) VALUES
                    ('PREVIEW_MESSAGE', '{"preview": {"language": "en"}}', 'STEWARD'),
                    ('POST_ALERT', '{"entry": "00000000-0000-0000-0000-000000000044", "mentions": []}', 'STEWARD');
                INSERT INTO smp_inbox (kind, payload, actor_kind) VALUES
                    ('PREVIEW_MESSAGE', '{"preview": {"language": "en"}}', 'STEWARD'),
                    ('CHECK_SETTINGS', '{"change": {}}', 'STEWARD');
                INSERT INTO hunger_games_inbox (kind, payload, actor_kind) VALUES
                    ('PREVIEW_MESSAGE', '{"preview": {"language": "en"}}', 'STEWARD'),
                    ('CHECK_SETTINGS', '{"change": {}}', 'STEWARD');
                """));

        upgraded.migrate(DatabaseRole.placeholders(DatabaseRole.PREFIX), "88");

        for (final String inbox : List.of("bot_inbox", "smp_inbox", "hunger_games_inbox")) {
            assertEquals(
                    List.of(inbox.equals("bot_inbox") ? "POST_ALERT" : "CHECK_SETTINGS"),
                    jdbi.withHandle(handle -> handle.createQuery("SELECT kind FROM " + inbox)
                            .mapTo(String.class)
                            .list()),
                    inbox);
        }
    }
}
