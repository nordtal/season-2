package eu.nordtal.season.stewardagent.run;

import static eu.nordtal.season.database.AdminTexts.TEXTS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.database.DatabaseText;
import eu.nordtal.season.database.update.UpdateReport;
import eu.nordtal.season.stewardagent.apply.ApplyResult;
import eu.nordtal.season.stewardagent.plan.Change;
import eu.nordtal.season.stewardagent.plan.UpdatePlan;
import eu.nordtal.season.stewardagent.source.RemoteFile;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** Tests that the install on start is reported as a run, and only when it changed something. */
class BootstrapTest {

    private static final Change PACKETEVENTS = new Change(
            "smp",
            "packetevents",
            Change.Status.MISSING,
            null,
            new RemoteFile(
                    "packetevents",
                    "2.13.0",
                    "packetevents-spigot-2.13.0.jar",
                    URI.create("https://example.invalid/p.jar"),
                    null),
            null);

    @Test
    void nothingMissingAndEverythingCheckedIsNoRunAtAll() {
        final Optional<UpdateReport> report = Bootstrap.report(plan(), missing -> {
            throw new AssertionError("nothing is installed when nothing is missing");
        });

        assertTrue(report.isEmpty(), "a restart with full volumes writes no run: " + report);
    }

    @Test
    void aMissingJarThatWentInIsAFinishedRunWithItsServiceInstalled() {
        final UpdateReport report = Bootstrap.report(
                        plan(PACKETEVENTS),
                        missing -> new ApplyResult(List.of(new ApplyResult.Outcome(
                                "smp",
                                "packetevents",
                                ApplyResult.Status.DONE,
                                "packetevents-spigot-2.13.0.jar",
                                "packetevents-spigot-2.13.0.jar"))))
                .orElseThrow();

        assertEquals(UpdateReport.Stage.DONE, report.stage());
        final UpdateReport.ServiceLine smp = report.line("smp");
        assertEquals(UpdateReport.State.INSTALLED, smp.state());
        assertEquals(List.of(new UpdateReport.Change("packetevents", null, "2.13.0")), smp.changes());
    }

    @Test
    void aJarThatDidNotGoInFailsTheRunAndSaysWhy() {
        final UpdateReport report = Bootstrap.report(
                        plan(PACKETEVENTS),
                        missing -> new ApplyResult(List.of(new ApplyResult.Outcome(
                                "smp", "packetevents", ApplyResult.Status.FAILED, "checksum did not match"))))
                .orElseThrow();

        assertEquals(UpdateReport.Stage.FAILED, report.stage());
        assertEquals(UpdateReport.State.FAILED, report.line("smp").state());
        assertTrue(
                report.failures().stream()
                        .anyMatch(failure -> "smp".equals(failure.service())
                                && DatabaseText.english(failure.what()).contains("checksum did not match")),
                "the bot's card and the alert say the failures: " + report.failures());
    }

    @Test
    void aJarLeftOutBecauseItsServerCouldNotBeCheckedFailsTheRun() {
        final UpdateReport report = Bootstrap.report(
                        plan(PACKETEVENTS),
                        missing -> new ApplyResult(List.of(new ApplyResult.Outcome(
                                "smp", "packetevents", ApplyResult.Status.SKIPPED, "the season jar is unknown"))))
                .orElseThrow();

        assertEquals(UpdateReport.Stage.FAILED, report.stage(), "skipping everything is not a finished install");
    }

    @Test
    void anArtefactThatCouldNotBeCheckedFailsTheRunEvenWithNothingMissing() {
        final UpdateReport report = Bootstrap.report(
                        plan(Change.unresolved("limbo", "limbo", TEXTS.report().words("GitHub answered 503"))),
                        missing -> {
                            throw new AssertionError("nothing is installed when nothing is missing");
                        })
                .orElseThrow();

        assertEquals(UpdateReport.Stage.FAILED, report.stage());
        assertEquals(UpdateReport.State.FAILED, report.line("limbo").state());
    }

    @Test
    void whatTheInstallWroteIsHandedToItsServicesUsersEvenWhenItFailedPartWay() {
        final List<List<String>> handed = new ArrayList<>();
        final Function<UpdatePlan, ApplyResult> apply = Bootstrap.handingOver(
                missing -> {
                    throw new IllegalStateException("disk full");
                },
                handed::add);

        assertThrows(IllegalStateException.class, () -> apply.apply(plan(PACKETEVENTS)));
        assertEquals(List.of(List.of("smp")), handed, "a jar written as root stays root's until the next update run");
    }

    @Test
    void anInstallThatThrowsIsAFailedRunThatKeepsWhatWasPlanned() {
        final UpdateReport report = Bootstrap.report(plan(PACKETEVENTS), missing -> {
                    throw new IllegalStateException("disk full");
                })
                .orElseThrow();

        assertEquals(UpdateReport.Stage.FAILED, report.stage());
        assertFalse(report.failures().isEmpty(), "a failed run says why");
        assertTrue(report.line("smp").isMoving(), "what it meant to install stays named");
    }

    private static UpdatePlan plan(final Change... changes) {
        return new UpdatePlan(Instant.EPOCH, "v0.20.0", false, List.of(changes), List.of(), List.of());
    }
}
