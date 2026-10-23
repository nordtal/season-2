package eu.nordtal.season.database;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.database.inbox.BotRequest;
import eu.nordtal.season.database.inbox.Inbox;
import eu.nordtal.season.database.inbox.Request;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;

/** Holds that an admin's announcement written as messages keeps its words and answer as a row per language. */
class AnnouncementRowsUpgradeIntegrationTest {

    @Test
    void anAdminsWordsKeepEachLanguageAndAMilestoneWithoutItsKeyGoes() {
        final TestDatabase upgraded = TestDatabase.empty();
        upgraded.migrate(DatabaseRole.placeholders(DatabaseRole.PREFIX), "86");
        Jdbi.create(upgraded.dataSource()).useHandle(handle -> handle.execute("""
                INSERT INTO bot_inbox (kind, payload, actor_kind, status, outcome, finished) VALUES ('ANNOUNCE',
                    '{"messages": {
                        "de": {"key": "announcement.words", "args": {"text": {"kind": "text", "value": "Heute *Ende*"}}},
                        "en": {"key": "announcement.words", "args": {"text": {"kind": "text", "value": "The *End*"}}}}}',
                    'STEWARD', 'DONE', '{"de": "NOT_POSTED", "en": "POSTED"}', now());
                INSERT INTO bot_inbox (kind, payload, actor_kind, status, outcome, finished) VALUES ('ANNOUNCE',
                    '{"messages": {"en": {"key": "announcement.milestone.border",
                        "args": {"milestone.name": {"kind": "text", "value": "Foothold"}}}}}',
                    'STEWARD', 'DONE', '{"en": "POSTED"}', now());
                """));

        upgraded.migrate(DatabaseRole.placeholders(DatabaseRole.PREFIX), "87");

        final Map<String, String> words = new LinkedHashMap<>();
        words.put("de", "Heute *Ende*");
        words.put("en", "The *End*");
        final List<Request<BotRequest>> kept =
                Inbox.over(upgraded.dataSource(), BotRequest.TABLE).recent(BotRequest.Announce.class, 10);
        assertEquals(1, kept.size(), "a milestone's announcement named no milestone, so it is gone");
        assertEquals(BotRequest.Announce.words(words), kept.getFirst().payload(), "its markdown as it was written");
        assertEquals(
                java.util.Optional.of(new BotRequest.Announce.Answer(List.of(
                        new BotRequest.Announce.Posted("de", false), new BotRequest.Announce.Posted("en", true)))),
                kept.getFirst().answer(BotRequest.Announce.Answer.class));
    }
}
