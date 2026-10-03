package eu.nordtal.s2.database;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.s2.database.alert.AlertBook;
import eu.nordtal.s2.database.inbox.BotRequest;
import eu.nordtal.s2.database.inbox.Inbox;
import eu.nordtal.s2.messages.MessageRef;
import eu.nordtal.s2.messages.Messages;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;

/**
 * Holds what telling an alert in messages does to the alerts and posts an installation already wrote.
 * Each keeps its words and renders through the admin bundle, a post the bot has not read yet included.
 */
class AlertMessageUpgradeIntegrationTest {

    private static final Messages ADMIN =
            Messages.load(AlertMessageUpgradeIntegrationTest.class.getClassLoader(), "messages/admin");

    @Test
    void everyAlertAndEveryPostKeepsItsWords() {
        final TestDatabase upgraded = TestDatabase.empty();
        upgraded.migrate(DatabaseRole.placeholders(DatabaseRole.PREFIX), "21");
        final Jdbi jdbi = Jdbi.create(upgraded.dataSource());
        jdbi.useHandle(handle -> handle.execute("""
                INSERT INTO admin_alert (raised_by, type, level, subject, title, detail, path, raised) VALUES
                    ('steward', 'SERVICE', 'DOWN', 'smp', 'smp is not running', 'Docker reports the state exited.',
                        '/services/smp', now() - interval '2 minutes'),
                    ('steward', 'DRIFT', 'OK', 'registry', 'All clear: registry', '', '/', now() - interval '1 minute');
                INSERT INTO bot_inbox (kind, payload, actor_kind) VALUES ('POST_ALERT',
                    '{"level": "DOWN", "title": "The update run failed",
                      "detail": "Run 15.\\nhttps://steward.example/operations/updates/15",
                      "mentions": ["100000000000000001"]}', 'STEWARD');
                """));

        upgraded.migrate(DatabaseRole.placeholders(DatabaseRole.PREFIX), "22");

        assertEquals(
                List.of("All clear: registry", "smp is not running | Docker reports the state exited."),
                AlertBook.using(upgraded.dataSource()).recent(10).stream()
                        .map(raised ->
                                shown(raised.alert().title(), raised.alert().detail()))
                        .toList(),
                "an alert keeps its title and its detail as one line");
        final BotRequest.PostAlert post = (BotRequest.PostAlert) Inbox.over(upgraded.dataSource(), BotRequest.TABLE)
                .claim()
                .orElseThrow()
                .payload();
        assertEquals(
                "The update run failed | Run 15.\nhttps://steward.example/operations/updates/15",
                shown(post.title(), post.detail()),
                "a post the bot had not read keeps its words, its link among them");
    }

    private static String shown(final MessageRef title, final List<MessageRef> detail) {
        return String.join(
                " | ",
                Stream.concat(Stream.of(title), detail.stream())
                        .map(message -> ADMIN.format(Locale.ENGLISH, message))
                        .toList());
    }
}
