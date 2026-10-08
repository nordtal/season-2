package eu.nordtal.season.database.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import eu.nordtal.season.database.DatabaseRole;
import eu.nordtal.season.database.TestDatabase;
import java.util.List;
import org.jdbi.v3.core.Jdbi;
import org.jspecify.annotations.Nullable;
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
        final String told = "{\"stage\": \"CANCELLED\", \"services\": [],"
                + " \"notes\": [{\"key\": \"report.cancelled\", \"args\": {}}]}";
        final long cancelled = settled(jdbi, "RESTART", "CANCELLED", "'" + told + "'::jsonb");

        upgraded.migrate(DatabaseRole.placeholders(DatabaseRole.PREFIX), "26");

        assertEquals("FAILED", at(jdbi, orphaned, "{stage}"), "a bare reason is a failed run's");
        assertEquals(
                List.of("report.words: " + ORPHANED), notes(jdbi, orphaned), "and its reason is the report's one note");
        assertEquals(
                List.of("report.words: limbo is being held down", "report.words: kept 7 daily and removed 2: a, b"),
                notes(jdbi, saved),
                "the notes keep their words and their order");
        assertEquals("UNVERIFIED STOP - see mc-smp.unverified", at(jdbi, saved, "{services,0,detail,args,text,value}"));
        assertEquals("saved 1.0 GiB", at(jdbi, saved, "{services,0,changes,0,to}"));
        assertNull(at(jdbi, saved, "{services,1,detail}"), "a line without a detail stays without one");
        assertEquals(List.of("report.cancelled: "), notes(jdbi, cancelled), "a report in messages stays as it was");
    }

    private static long settled(final Jdbi jdbi, final String kind, final String status, final String outcome) {
        return jdbi.withHandle(handle -> handle.createQuery(
                        "INSERT INTO steward_inbox (kind, payload, actor_kind, status, started, finished, outcome)"
                                + " VALUES ('" + kind + "', '{\"services\": []}', 'HOST', '" + status + "', now(),"
                                + " now(), " + outcome + ") RETURNING id")
                .mapTo(Long.class)
                .one());
    }

    private static @Nullable String at(final Jdbi jdbi, final long id, final String path) {
        return jdbi.withHandle(handle -> handle.createQuery(
                        "SELECT outcome #>> CAST(:path AS text[]) FROM steward_inbox WHERE id = :id")
                .bind("path", path)
                .bind("id", id)
                .mapTo(String.class)
                .findOne()
                .orElse(null));
    }

    /** Each note as its key and its words, as the report stored them at this version. */
    private static List<String> notes(final Jdbi jdbi, final long id) {
        return jdbi.withHandle(handle ->
                handle.createQuery("""
                        SELECT (note ->> 'key') || ': ' || coalesce(note #>> '{args,text,value}', '')
                        FROM steward_inbox, jsonb_array_elements(outcome -> 'notes') WITH ORDINALITY AS kept(note, position)
                        WHERE id = :id ORDER BY position""").bind("id", id).mapTo(String.class).list());
    }
}
