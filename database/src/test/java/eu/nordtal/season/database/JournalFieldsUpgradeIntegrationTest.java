package eu.nordtal.season.database;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.database.audit.AuditDirectory;
import eu.nordtal.season.database.audit.AuditEntry;
import eu.nordtal.season.database.audit.JournalFilter;
import java.util.List;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;

/** Holds that a line written before the journal's typed fields can be found by its reference and its days. */
class JournalFieldsUpgradeIntegrationTest {

    @Test
    void aLineWrittenBeforeTheFieldsIsFoundByItsReferenceAndDays() {
        final TestDatabase upgraded = TestDatabase.empty();
        upgraded.migrate(DatabaseRole.placeholders(DatabaseRole.PREFIX), "40");
        Jdbi.create(upgraded.dataSource()).useHandle(handle -> handle.execute("""
                INSERT INTO audit_log (occurred, action, actor_kind, subject, line) VALUES
                    (now() - interval '3 minutes', 'SETTLE', 'STEWARD', '100000000000000001',
                        '{"key": "journal.settle-by-hand", "args": {
                            "reference": {"kind": "text", "value": "NT-ABCD"},
                            "days": {"kind": "number", "value": 30},
                            "ordered": {"kind": "number", "value": 30}}}'),
                    (now() - interval '2 minutes', 'GRANT_ACCESS', 'STEWARD', '100000000000000002',
                        '{"key": "journal.grant-access", "args": {
                            "days": {"kind": "number", "value": 7},
                            "until": {"kind": "instant", "value": "2026-11-02T10:00:00Z"}}}'),
                    (now() - interval '1 minutes', 'COMPLETE_OBJECTIVE', 'STEWARD', NULL,
                        '{"key": "journal.written", "args": {"detail": {"kind": "text", "value": "NT-ABCD"}}}');
                """));

        upgraded.migrate(DatabaseRole.placeholders(DatabaseRole.PREFIX), "latest");

        final AuditDirectory journal = AuditDirectory.using(upgraded.dataSource());
        assertEquals(
                List.of("SETTLE"),
                actions(journal.search(new JournalFilter(null, null, "NT-ABCD", null), 10)),
                "the reference comes from the value of that name, not from any text that reads like one");
        assertEquals(
                List.of("GRANT_ACCESS", "SETTLE"), actions(journal.search(new JournalFilter(null, null, null, 7), 10)));
        assertEquals(List.of("SETTLE"), actions(journal.search(new JournalFilter(null, null, null, 8), 10)));
    }

    private static List<String> actions(final List<AuditEntry> entries) {
        return entries.stream().map(AuditEntry::action).toList();
    }
}
