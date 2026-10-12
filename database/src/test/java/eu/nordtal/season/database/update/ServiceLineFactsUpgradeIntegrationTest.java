package eu.nordtal.season.database.update;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.database.DatabaseRole;
import eu.nordtal.season.database.DatabaseText;
import eu.nordtal.season.database.TestDatabase;
import eu.nordtal.season.database.update.UpdateReport.Facts;
import eu.nordtal.season.messages.MessageRef;
import java.util.ArrayList;
import java.util.List;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Holds what giving a run's service lines their facts does to the reports an installation already wrote.
 * Each line's message becomes the facts it was composed from, and reads as before; a restored archive says so.
 */
class ServiceLineFactsUpgradeIntegrationTest {

    private static final String BEFORE = "45";
    private static final String AFTER = "46";

    private static String text(final String value) {
        return String.format("{\"kind\": \"text\", \"value\": \"%s\"}", value);
    }

    private static String number(final long value) {
        return String.format("{\"kind\": \"number\", \"value\": %d}", value);
    }

    private static String choice(final boolean value) {
        return String.format("{\"kind\": \"choice\", \"value\": %b}", value);
    }

    private static String arg(final String name, final String value) {
        return String.format("\"%s\": %s", name, value);
    }

    private static String message(final String key, final String... args) {
        return String.format("{\"key\": \"%s\", \"args\": {%s}}", key, String.join(", ", args));
    }

    private static String nested(final String key, final String... args) {
        return String.format("{\"kind\": \"message\", \"value\": %s}", message(key, args));
    }

    private static String line(final String service, final String state, final String changes, final String detail) {
        return String.format(
                "{\"service\": \"%s\", \"state\": \"%s\", \"changes\": [%s], \"detail\": %s}",
                service, state, changes, detail);
    }

    private static String told(final String artefact, final String key) {
        return String.format(
                "{\"artefact\": \"%s\", \"to\": \"-\", \"state\": \"MOVING\", \"told\": %s}", artefact, message(key));
    }

    private static UpdateDirectory runs;
    private static Jdbi jdbi;
    private static long update;
    private static long backup;
    private static long restore;

    @BeforeAll
    static void upgradeThreeStoredRuns() {
        final TestDatabase upgraded = TestDatabase.empty();
        upgraded.migrate(DatabaseRole.placeholders(DatabaseRole.PREFIX), BEFORE);
        jdbi = Jdbi.create(upgraded.dataSource());
        final List<String> lines = new ArrayList<>(waitedLines());
        lines.addAll(stepLines());
        update = settled("UPDATE", "FAILED", lines);
        backup = settled(
                "BACKUP",
                "FAILED",
                List.of(
                        line(
                                "nordtal-s2_mc-smp",
                                "SAVED",
                                told("backup", "report.saving"),
                                message("report.unverified-archive", arg("mark", text("smp-1.tar.zst.unverified")))),
                        line(
                                "database",
                                "FAILED",
                                told("backup", "report.not-saved"),
                                message("report.words", arg("text", text("pg_dump failed"))))));
        restore = settled(
                "RESTORE",
                "DONE",
                List.of(
                        line("smp-1.tar.zst", "INSTALLED", told("restore", "report.restored"), "null"),
                        line("smp", "HEALTHY", told("restore", "report.stopped-for-restore"), "null")));
        upgraded.migrate(DatabaseRole.placeholders(DatabaseRole.PREFIX), AFTER);
        runs = UpdateDirectory.using(upgraded.dataSource());
    }

    /** Lines that waited for a service or fell back, each with what was seen. */
    private static List<String> waitedLines() {
        final String starting = arg("seen", nested("report.words", arg("text", text("running, starting"))));
        return List.of(
                line("smp", "FAILED", "", message("report.not-healthy", arg("minutes", number(5)), starting)),
                line(
                        "limbo",
                        "FAILED",
                        "",
                        message(
                                "report.fell-back-down",
                                arg("outdated", choice(true)),
                                arg("reason", text("no such image")),
                                arg("minutes", number(5)),
                                arg("seen", nested("report.no-container")))),
                line(
                        "proxy",
                        "FAILED",
                        "",
                        message(
                                "report.not-fell-back",
                                arg("outdated", choice(false)),
                                arg("reason", text("refused")),
                                arg("answer", text("gone")))),
                line(
                        "hunger-games",
                        "FAILED",
                        "",
                        message(
                                "report.not-healthy",
                                arg("minutes", number(1)),
                                arg("seen", nested("report.runtime-unread", arg("answer", text("timed out")))))),
                line("velocity", "FAILED", "", message("report.wait-interrupted")));
    }

    /** Lines of a step that ran, failed or was told in words, and one only a source spoke about. */
    private static List<String> stepLines() {
        return List.of(
                line(
                        "caddy",
                        "HEALTHY",
                        "",
                        message("report.words", arg("text", text("pulling its image and recreating the container")))),
                line("steward", "STARTING", "", message("report.recreating", arg("pull", choice(false)))),
                line("steward-agent", "STARTING", "", message("report.renewing-agent")),
                line("discord-bot", "FAILED", "", message("report.stop-failed", arg("reason", text("refused")))),
                line("steward-bunq", "STOPPED", "", message("report.words", arg("text", text("ended unread")))),
                line(
                        "paper",
                        "FAILED",
                        "",
                        message("report.not-installed", arg("artefact", text("smp")), arg("reason", text("full")))),
                line("postgres", "FAILED", "", message("report.foreign-not-recreated", arg("reason", text("refused")))),
                line("hub", "FAILED", "", message("report.no-source", arg("artefact", text("chunky")))));
    }

    @Test
    void everyLineOfAnUpdateReadsAsItDidNowDrawnFromItsFacts() {
        assertEquals(
                List.of(
                        "smp FAILED: not back within 5 minutes (running, starting)",
                        "limbo FAILED: image out of date, not recreated: no such image; on the old image and not back"
                                + " within 5 minutes (no container for it in the project)",
                        "proxy FAILED: not recreated: refused; starting the old image failed too: gone",
                        "hunger-games FAILED: not back within 1 minute (the container runtime could not be read:"
                                + " timed out)",
                        "velocity FAILED: steward-agent stopped while waiting for this service",
                        "caddy HEALTHY: -",
                        "steward STARTING: recreating the container",
                        "steward-agent STARTING: recreating the container at this release",
                        "discord-bot FAILED: could not be stopped: refused",
                        "steward-bunq STOPPED: ended unread",
                        "paper FAILED: smp: full",
                        "postgres FAILED: not recreated, so the old one still runs: refused",
                        "hub FAILED: -"),
                said(update));
        final UpdateReport updated = report(update);
        assertEquals(
                Facts.notBack(UpdateReport.Back.NO, 5L, new Facts.Seen("running", "starting", null)),
                updated.line("smp").facts());
        assertEquals(
                Facts.notRecreated(UpdateReport.Image.OUTDATED, "no such image", UpdateReport.Fallback.STARTED, null)
                        .came(UpdateReport.Back.NO, 5L, new Facts.Seen(null, null, null)),
                updated.line("limbo").facts());
        assertEquals(
                Facts.of(UpdateReport.Act.RECREATE).made(UpdateReport.Image.PULLED),
                updated.line("caddy").facts(),
                "a recreate an early run told in words is a recreate");
    }

    @Test
    void whatASourceAnsweredAboutAServiceBecomesARecordOfTheSources() {
        assertEquals(
                List.of("SOURCES FAILED hub: no source answered for chunky"),
                report(update).notes().stream()
                        .map(note -> note.step() + " " + note.outcome() + " " + note.service() + ": "
                                + DatabaseText.english(note.what()))
                        .toList());
    }

    @Test
    void anArchiveKeepsItsMarkAsAFactAndAFailedSaveItsReason() {
        assertEquals(
                List.of(
                        "nordtal-s2_mc-smp SAVED: stop not verified, marked beside smp-1.tar.zst",
                        "database FAILED: pg_dump failed"),
                said(backup));
        assertEquals(
                Facts.saved("smp-1.tar.zst", UpdateReport.Mark.WRITTEN),
                report(backup).line("nordtal-s2_mc-smp").facts());
        assertEquals(
                Facts.refused(UpdateReport.Act.BACKUP, "pg_dump failed"),
                report(backup).line("database").facts());
    }

    @Test
    void anArchivePutBackIsRestoredNotUpdated() {
        assertEquals(List.of("smp-1.tar.zst RESTORED: -", "smp HEALTHY: -"), said(restore));
    }

    @Test
    void noLineIsLeftInTheOldShape() {
        final long oldShape = jdbi.withHandle(handle -> handle.createQuery("SELECT count(*) FROM steward_inbox AS run"
                        + " CROSS JOIN LATERAL jsonb_array_elements(run.outcome -> 'services') AS line"
                        + " WHERE line -> 'detail' IS NOT NULL")
                .mapTo(Long.class)
                .one());
        assertEquals(0, oldShape);
    }

    private static long settled(final String kind, final String status, final List<String> lines) {
        final String outcome = String.format(
                "{\"stage\": \"%s\", \"services\": [%s], \"notes\": []}", status, String.join(", ", lines));
        return jdbi.withHandle(handle -> handle.createQuery(
                        "INSERT INTO steward_inbox (kind, payload, actor_kind, status, started, finished, outcome)"
                                + " VALUES (:kind, '{\"services\": []}', 'HOST', :status, now(), now(),"
                                + " CAST(:outcome AS jsonb)) RETURNING id")
                .bind("kind", kind)
                .bind("status", status)
                .bind("outcome", outcome)
                .mapTo(Long.class)
                .one());
    }

    private static UpdateReport report(final long id) {
        return UpdateReports.parse(runs.find(id).orElseThrow().result()).orElseThrow();
    }

    private static List<String> said(final long id) {
        return report(id).services().stream()
                .map(line -> {
                    final MessageRef said = line.said();
                    return line.service() + " " + line.state() + ": "
                            + (said == null ? "-" : DatabaseText.english(said));
                })
                .toList();
    }
}
