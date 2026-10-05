package eu.nordtal.season.database;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.database.inbox.BotRequest;
import eu.nordtal.season.database.inbox.Inbox;
import eu.nordtal.season.database.inbox.Request;
import eu.nordtal.season.messages.Messages;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;

/**
 * Holds what telling an announcement in messages does to the announcements an installation already wrote.
 * Each language keeps its text and renders through the database bundle, one the bot has not posted yet included.
 */
class AnnouncementMessageUpgradeIntegrationTest {

    private static final Messages DATABASE = Messages.load(
            AnnouncementMessageUpgradeIntegrationTest.class.getClassLoader(), "messages/database", Locale.GERMAN);

    @Test
    void everyAnnouncementKeepsEachLanguagesText() {
        final TestDatabase upgraded = TestDatabase.empty();
        upgraded.migrate(DatabaseRole.placeholders(DatabaseRole.PREFIX), "23");
        Jdbi.create(upgraded.dataSource()).useHandle(handle -> handle.execute("""
                INSERT INTO bot_inbox (kind, payload, actor_kind, status, outcome, finished) VALUES ('ANNOUNCE',
                    '{"texts": {"en": "Foothold is complete.", "de": "Foothold ist geschafft."}}', 'STEWARD',
                    'DONE', '{"en": "POSTED", "de": "NOT_POSTED"}', now());
                INSERT INTO bot_inbox (kind, payload, actor_kind, actor_id) VALUES ('ANNOUNCE',
                    '{"texts": {"en": "The *End* opens tonight.", "de": "Das Ende öffnet heute."}}', 'PERSON',
                    '100000000000000001');
                """));

        upgraded.migrate(DatabaseRole.placeholders(DatabaseRole.PREFIX), "24");

        final Inbox<BotRequest> bot = Inbox.over(upgraded.dataSource(), BotRequest.TABLE);
        assertEquals(
                List.of(
                        Map.of("en", "The *End* opens tonight.", "de", "Das Ende öffnet heute."),
                        Map.of("en", "Foothold is complete.", "de", "Foothold ist geschafft.")),
                bot.recent(BotRequest.Announce.class, 10).stream()
                        .map(AnnouncementMessageUpgradeIntegrationTest::shown)
                        .toList(),
                "every language keeps its text, its markdown as it was written");
        assertEquals(
                Map.of("en", "The *End* opens tonight.", "de", "Das Ende öffnet heute."),
                shown(bot.claim().orElseThrow()),
                "an announcement the bot had not posted keeps its words");
    }

    private static Map<String, String> shown(final Request<BotRequest> row) {
        final Map<String, String> shown = new LinkedHashMap<>();
        ((BotRequest.Announce) row.payload())
                .messages()
                .forEach((tag, message) -> shown.put(tag, DATABASE.format(Locale.forLanguageTag(tag), message)));
        return shown;
    }
}
