package eu.nordtal.s2.database.update;

import static eu.nordtal.s2.database.AdminTexts.TEXTS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import eu.nordtal.s2.database.DatabaseRole;
import eu.nordtal.s2.database.DatabaseText;
import eu.nordtal.s2.database.TestDatabase;
import java.util.List;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;

/**
 * Holds what telling a run's report in messages does to the reports an installation already wrote.
 * Each keeps its words in its order, a bare reason becomes a report, and one already in messages stays as it is.
 */
class RunReportMessageUpgradeIntegrationTest {

    private static final String ORPHANED = "The steward-agent carrying out this request stopped before it finished.";

    @Test
    void everyReportKeepsItsWordsInItsOrder() {
        final TestDatabase upgraded = TestDatabase.empty();
        upgraded.migrate(DatabaseRole.placeholders(DatabaseRole.PREFIX), "25");
        final Jdbi jdbi = Jdbi.create(upgraded.dataSource());
        final long orphaned = settled(jdbi, "UPDATE", "FAILED", "to_jsonb('" + ORPHANED + "'::text)");
        final long saved = settled(jdbi, "BACKUP", "DONE", """
                '{"stage": "DONE",
                  "services": [
                    {"service": "mc-smp", "state": "SAVED", "detail": "UNVERIFIED STOP - see mc-smp.unverified",
                     "changes": [{"artefact": "backup", "from": null, "to": "saved 1.0 GiB", "state": "MOVING"}]},
                    {"service": "database", "state": "SAVED", "detail": null, "changes": []}],
                  "notes": ["limbo is being held down", "kept 7 daily and removed 2: a, b"]}'::jsonb""");
        final String told = UpdateReports.toJson(UpdateReport.at(UpdateReport.Stage.CANCELLED)
                .withNote(TEXTS.report().cancelled()));
        final long cancelled = settled(jdbi, "RESTART", "CANCELLED", "'" + told + "'::jsonb");

        upgraded.migrate(DatabaseRole.placeholders(DatabaseRole.PREFIX), "26");
        final UpdateDirectory runs = UpdateDirectory.using(upgraded.dataSource());

        final UpdateReport bare = report(runs, orphaned);
        assertEquals(UpdateReport.Stage.FAILED, bare.stage(), "a bare reason is a failed run's");
        assertEquals(List.of(ORPHANED), english(bare.notes()), "and its reason is the report's one note");

        final UpdateReport backup = report(runs, saved);
        assertEquals(
                List.of("limbo is being held down", "kept 7 daily and removed 2: a, b"),
                english(backup.notes()),
                "the notes keep their words and their order");
        assertEquals(
                "UNVERIFIED STOP - see mc-smp.unverified",
                DatabaseText.english(
                        java.util.Objects.requireNonNull(backup.line("mc-smp").detail())));
        assertEquals("saved 1.0 GiB", backup.line("mc-smp").changes().getFirst().to());
        assertNull(backup.line("database").detail(), "a line without a detail stays without one");

        assertEquals(
                UpdateReports.parse(told),
                UpdateReports.parse(runs.find(cancelled).orElseThrow().result()));
    }

    private static long settled(final Jdbi jdbi, final String kind, final String status, final String outcome) {
        return jdbi.withHandle(handle -> handle.createQuery(
                        "INSERT INTO steward_inbox (kind, payload, actor_kind, status, started, finished, outcome)"
                                + " VALUES ('" + kind + "', '{\"services\": []}', 'HOST', '" + status + "', now(),"
                                + " now(), " + outcome + ") RETURNING id")
                .mapTo(Long.class)
                .one());
    }

    private static UpdateReport report(final UpdateDirectory runs, final long id) {
        return UpdateReports.parse(runs.find(id).orElseThrow().result()).orElseThrow();
    }

    private static List<String> english(final List<eu.nordtal.s2.messages.MessageRef> notes) {
        return notes.stream().map(DatabaseText::english).toList();
    }
}
