package eu.nordtal.season.database;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.database.alert.AlertBook;
import eu.nordtal.season.database.inbox.BotRequest;
import eu.nordtal.season.database.inbox.Inbox;
import eu.nordtal.season.messages.Messages;
import java.util.List;
import java.util.Locale;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;

/** Holds that alerts and posts written before a failed run was titled as its card keep their kind. */
class RunAlertTitleUpgradeIntegrationTest {

    private static final Messages ADMIN =
            Messages.load(RunAlertTitleUpgradeIntegrationTest.class.getClassLoader(), "messages/admin");

    @Test
    void aFailedRunsAlertIsTitledByItsKind() {
        final TestDatabase upgraded = TestDatabase.empty();
        upgraded.migrate(DatabaseRole.placeholders(DatabaseRole.PREFIX), "42");
        final Jdbi jdbi = Jdbi.create(upgraded.dataSource());
        jdbi.useHandle(handle -> handle.execute("""
                INSERT INTO admin_alert (raised_by, type, level, subject, title, detail, path, raised) VALUES
                    ('steward', 'RUN', 'DOWN', 'backup',
                        '{"key": "alert.run-failed", "args": {"kind": {"kind": "choice", "value": "backup"}}}',
                        '[{"key": "alert.run", "args": {"run": {"kind": "number", "value": 74}}}]',
                        '/operations/updates/74', now() - interval '2 minutes'),
                    ('steward', 'DISK', 'WARN', 'disk', '{"key": "alert.disk", "args":
                        {"percent": {"kind": "number", "value": 91}}}', '[]', '/', now() - interval '1 minute');
                INSERT INTO bot_inbox (kind, payload, actor_kind) VALUES ('POST_ALERT',
                    '{"link": "https://steward.example/operations/updates/75", "level": "DOWN",
                      "title": {"key": "alert.run-failed", "args": {"kind": {"kind": "text", "value": "remove-plugin"}}},
                      "detail": [], "mentions": []}', 'STEWARD');
                """));

        upgraded.migrate(DatabaseRole.placeholders(DatabaseRole.PREFIX), "latest");

        assertEquals(
                List.of("The disk is 91 % full", "The backup failed"),
                AlertBook.using(upgraded.dataSource()).recent(10).stream()
                        .map(raised ->
                                ADMIN.format(Locale.ENGLISH, raised.alert().title()))
                        .toList(),
                "a run's alert takes the card's title, and every other alert keeps its own");
        final BotRequest.PostAlert post = (BotRequest.PostAlert) Inbox.over(upgraded.dataSource(), BotRequest.TABLE)
                .claim()
                .orElseThrow()
                .payload();
        assertEquals("The plugin removal failed", ADMIN.format(Locale.ENGLISH, post.title()));
    }
}
