package eu.nordtal.season.database.update;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.database.DatabaseRole;
import eu.nordtal.season.database.DatabaseText;
import eu.nordtal.season.database.TestDatabase;
import java.util.List;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;

/**
 * Holds what making a run's notes records does to the reports an installation already wrote.
 * Each note gets its step, outcome and service, one per service it named, in its order, and still reads.
 */
class RunNotesRecordUpgradeIntegrationTest {

    private static String text(final String value) {
        return "{\"kind\": \"text\", \"value\": \"" + value + "\"}";
    }

    private static String list(final String... values) {
        return "{\"kind\": \"list\", \"value\": [" + String.join(", ", values) + "]}";
    }

    @Test
    void everyNoteBecomesARecordPerServiceInItsOrder() {
        final TestDatabase upgraded = TestDatabase.empty();
        upgraded.migrate(DatabaseRole.placeholders(DatabaseRole.PREFIX), "36");
        final Jdbi jdbi = Jdbi.create(upgraded.dataSource());
        final long update = anUpdate(jdbi);
        final long refused = aRefusal(jdbi);
        final long backup = aBackup(jdbi);

        upgraded.migrate(DatabaseRole.placeholders(DatabaseRole.PREFIX), "37");
        final UpdateDirectory runs = UpdateDirectory.using(upgraded.dataSource());

        assertEquals(
                List.of(
                        "STANDBY DONE limbo-standby: started and healthy",
                        "STANDBY DONE proxy-standby: started and healthy",
                        "CLEANUP DONE -: removed 13 unused images, freed 3.8 GiB",
                        "STANDBY DONE limbo-standby: stopped again",
                        "RUN DONE -: as said"),
                records(runs, update),
                "a list of services becomes one record each, and words stay the run's own");
        assertEquals(
                List.of(
                        "STANDBY FAILED -: no standby came up, so nothing was stopped",
                        "STANDBY FAILED limbo-standby: not healthy within 3 minutes (no container for it in the project)",
                        "RELEASE FAILED steward: runs a local build this run would replace, so nothing was stopped",
                        "RUN FAILED -: as said"),
                records(runs, refused),
                "a standby's health names it, and words in a failed run are a failure");
        assertEquals(
                List.of(
                        "STOP FAILED smp: stopped, but how it ended could not be read back",
                        "PLAYERS WARNING smp: stopped with 3 players still on after 10s",
                        "PLAYERS WARNING hunger-games: stopped with 1 player still on after 10s",
                        "SCOPE FAILED steward-agent: carries the run out, so it cannot make its own container again"),
                records(runs, backup),
                "a stop that fails the run is a failure, and each server keeps its own player count");
    }

    /** An update that went well, with a list of standbys, a size and words. */
    private static long anUpdate(final Jdbi jdbi) {
        return settled(jdbi, "DONE", """
                [{"key": "report.standbys-ready", "args": {"run": {"kind": "choice", "value": "install"},
                  "standbys": %s}},
                 {"key": "report.images-pruned", "args": {"images": {"kind": "number", "value": 13},
                  "freed": {"kind": "message", "value": {"key": "report.size", "args": {
                    "unit": {"kind": "choice", "value": "gib"}, "amount": {"kind": "number", "value": 3.8}}}}}},
                 {"key": "report.standby-stopped", "args": {"standby": %s}},
                 {"key": "report.words", "args": {"text": %s}}]""".formatted(
                        list(text("limbo-standby"), text("proxy-standby")), text("limbo-standby"), text("as said")));
    }

    /** A refused update, with a standby named inside a message and a failed run's words. */
    private static long aRefusal(final Jdbi jdbi) {
        return settled(jdbi, "FAILED", """
                [{"key": "report.no-standby", "args": {"run": {"kind": "choice", "value": "install"}}},
                 {"key": "report.standbys-unhealthy", "args": {"minutes": {"kind": "number", "value": 3},
                  "standbys": %s}},
                 {"key": "report.local-builds-kept", "args": {"count": {"kind": "number", "value": 1},
                  "services": %s}},
                 {"key": "report.words", "args": {"text": %s}}]""".formatted(
                        list("""
                                {"kind": "message", "value": {"key": "report.standby-seen", "args": {
                                  "standby": %s, "seen": {"kind": "message", "value": {"key": "report.no-container",
                                  "args": {}}}}}}""".formatted(text("limbo-standby"))), list(text("steward")), text("as said")));
    }

    /** A backup with an unverified stop that failed it and a player count per server. */
    private static long aBackup(final Jdbi jdbi) {
        return settled(jdbi, "FAILED", """
                [{"key": "report.unverified-stop", "args": {"services": %s,
                  "run": {"kind": "choice", "value": "backup"}, "failsTheRun": {"kind": "choice", "value": true}}},
                 {"key": "report.stopped-with-players", "args": {"players": {"kind": "number", "value": 4},
                  "servers": %s, "seconds": {"kind": "number", "value": 10}}},
                 {"key": "report.remake-agent", "args": {}}]""".formatted(list(text("smp")), list(text("smp: 3"), text("hunger-games: 1"))));
    }

    private static long settled(final Jdbi jdbi, final String status, final String notes) {
        final String outcome = "{\"stage\": \"" + status + "\", \"services\": [], \"notes\": " + notes + "}";
        return jdbi.withHandle(handle -> handle.createQuery(
                        "INSERT INTO steward_inbox (kind, payload, actor_kind, status, started, finished, outcome)"
                                + " VALUES ('UPDATE', '{\"services\": []}', 'HOST', :status, now(), now(),"
                                + " CAST(:outcome AS jsonb)) RETURNING id")
                .bind("status", status)
                .bind("outcome", outcome)
                .mapTo(Long.class)
                .one());
    }

    private static List<String> records(final UpdateDirectory runs, final long id) {
        return UpdateReports.parse(runs.find(id).orElseThrow().result()).orElseThrow().notes().stream()
                .map(note -> note.step() + " " + note.outcome() + " " + (note.service() == null ? "-" : note.service())
                        + ": " + DatabaseText.english(note.what()))
                .toList();
    }
}
