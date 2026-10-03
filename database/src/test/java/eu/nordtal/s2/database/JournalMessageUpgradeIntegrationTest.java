package eu.nordtal.s2.database;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.s2.common.json.Json;
import eu.nordtal.s2.messages.MessageJson;
import eu.nordtal.s2.messages.Messages;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;

/**
 * Holds what making a journal line a message does to the lines an installation already wrote.
 *
 * Each one renders through the admin bundle with every value its text names, as a line written today would.
 */
class JournalMessageUpgradeIntegrationTest {

    private static final Messages ADMIN =
            Messages.load(JournalMessageUpgradeIntegrationTest.class.getClassLoader(), "messages/admin");

    @Test
    void everyLineTheReleaseWroteRendersAsItsActionDoesToday() {
        final TestDatabase upgraded = TestDatabase.empty();
        upgraded.migrate(DatabaseRole.placeholders(DatabaseRole.PREFIX), "20");
        final Jdbi jdbi = Jdbi.create(upgraded.dataSource());
        jdbi.useHandle(handle -> handle.execute("""
                INSERT INTO audit_log (occurred, action, actor_kind, actor_id, facts) VALUES
                    (now() - interval '19 minutes', 'GRANT_ACCESS', 'STEWARD', NULL,
                        '{"days": 30, "until": "2026-11-02T10:00:00Z"}'),
                    (now() - interval '18 minutes', 'UNLINK', 'PERSON', '100000000000000001', '{"selfService": true}'),
                    (now() - interval '17 minutes', 'SET_PLAYTIME', 'HOST', NULL, '{"seconds": 7200}'),
                    (now() - interval '16 minutes', 'REVOKE_ADMIN', 'PERSON', '100000000000000001',
                        '{"below": ["100000000000000002", "100000000000000003"]}'),
                    (now() - interval '15 minutes', 'HELD_KEY', 'PERSON', '100000000000000001',
                        '{"label": "YUBI", "unlocked": true}'),
                    (now() - interval '14 minutes', 'CANCEL_RUN', 'PERSON', '100000000000000001',
                        '{"run": 140, "kind": "REMOVE_PLUGIN"}'),
                    (now() - interval '13 minutes', 'SET_PHASE', 'PERSON', '100000000000000001',
                        '{"from": "PRE_LAUNCH", "to": "SMP", "reason": "go"}'),
                    (now() - interval '12 minutes', 'SET_LAUNCH', 'PERSON', '100000000000000001',
                        jsonb_build_object('to', timestamptz '2026-10-10 18:00+00')),
                    (now() - interval '11 minutes', 'SET_LAUNCH', 'PERSON', '100000000000000001',
                        '{"from": "2026-10-10T18:00:00+00:00"}'),
                    (now() - interval '10 minutes', 'SET_SMP_START', 'PERSON', '100000000000000001',
                        jsonb_build_object('to', timestamptz '2026-10-11 18:00+00', 'movedGrants', 4)),
                    (now() - interval '9 minutes', 'ANNOUNCE', 'PERSON', '100000000000000001',
                        '{"languages": ["de", "en"]}'),
                    (now() - interval '8 minutes', 'COMPLETE_OBJECTIVE', 'PERSON', '100000000000000001',
                        '{"target": "first-diamond"}'),
                    (now() - interval '7 minutes', 'COMMAND', 'PERSON', '100000000000000001',
                        '{"detail": "asked from the web interface", "target": "/announce"}');
                """));

        upgraded.migrate(DatabaseRole.placeholders(DatabaseRole.PREFIX), "21");

        assertEquals(
                List.of(
                        "journal.grant-access: 30 days of access, until Nov 2, 2026.",
                        "journal.unlink: Unlinked their own Minecraft account.",
                        "journal.set-playtime: Play time set to 2h.",
                        "journal.revoke-admin: Admin revoked, and from 100000000000000002 and 100000000000000003"
                                + " below them.",
                        "journal.held-key: Unlocked the security key \"YUBI\".",
                        "journal.cancel-run: Cancelled run 140, a remove-plugin.",
                        "journal.set-phase-because: Phase pre-launch to smp: go",
                        "journal.set-launch: The network opens Oct 10, 2026, 6:00\u202fPM.",
                        "journal.clear-launch: The opening date was taken away.",
                        "journal.set-smp-start: Paid time starts Oct 11, 2026, 6:00\u202fPM; 4 grants moved with it.",
                        "journal.announce: Announced in de and en.",
                        "journal.written: first-diamond",
                        "journal.written: /announce: asked from the web interface"),
                jdbi.withHandle(handle -> handle.createQuery("SELECT line::text FROM audit_log ORDER BY occurred")
                        .mapTo(String.class)
                        .map(line -> MessageJson.decode(Json.decode(line, Map.class)))
                        .map(message -> message.key() + ": " + ADMIN.format(Locale.ENGLISH, message))
                        .list()),
                "a typed fact keeps its name and gains its kind, a variant takes its own key, and old words stay");
    }
}
