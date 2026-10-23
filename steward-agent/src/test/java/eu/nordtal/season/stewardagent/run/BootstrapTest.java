package eu.nordtal.season.stewardagent.run;

import static eu.nordtal.season.database.AdminTexts.TEXTS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.database.update.RunStage;
import eu.nordtal.season.database.update.RunStep;
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

    private final CollectedSteps steps = new CollectedSteps();

    @Test
    void nothingMissingAndEverythingCheckedIsNoRunAtAll() {
        final Optional<RunStage> stage = report(plan(), missing -> {
            throw new AssertionError("nothing is installed when nothing is missing");
        });

        assertTrue(stage.isEmpty(), "a restart with full volumes writes no run: " + stage);
        assertEquals(List.of(), steps.rows());
    }

    @Test
    void aMissingJarThatWentInIsAFinishedRunWithItsServiceInstalled() {
        final RunStage stage = report(
                        plan(PACKETEVENTS),
                        missing -> new ApplyResult(List.of(new ApplyResult.Outcome(
                                "smp",
                                "packetevents",
                                ApplyResult.Status.DONE,
                                "packetevents-spigot-2.13.0.jar",
                                "packetevents-spigot-2.13.0.jar"))))
                .orElseThrow();

        assertEquals(RunStage.DONE, stage);
    }

    @Test
    void aJarThatDidNotGoInFailsTheRunAndItsInstallStep() {
        final RunStage stage = report(
                        plan(PACKETEVENTS),
                        missing -> new ApplyResult(List.of(new ApplyResult.Outcome(
                                "smp", "packetevents", ApplyResult.Status.FAILED, "checksum did not match"))))
                .orElseThrow();

        assertEquals(RunStage.FAILED, stage);
        final RunStep.Row install = steps.rows().getLast();
        assertEquals(RunStep.Kind.INSTALL, install.step());
        assertEquals("smp", install.service());
        assertEquals(RunStep.Outcome.FAILED, install.outcome(), "the bot's card and the alert say the failures");
    }

    @Test
    void aJarLeftOutBecauseItsServerCouldNotBeCheckedFailsTheRun() {
        final RunStage stage = report(
                        plan(PACKETEVENTS),
                        missing -> new ApplyResult(List.of(new ApplyResult.Outcome(
                                "smp", "packetevents", ApplyResult.Status.SKIPPED, "the season jar is unknown"))))
                .orElseThrow();

        assertEquals(RunStage.FAILED, stage, "skipping everything is not a finished install");
    }

    @Test
    void anArtefactThatCouldNotBeCheckedFailsTheRunEvenWithNothingMissing() {
        final RunStage stage = report(
                        plan(Change.unresolved("limbo", "limbo", TEXTS.report().words("GitHub answered 503"))),
                        missing -> {
                            throw new AssertionError("nothing is installed when nothing is missing");
                        })
                .orElseThrow();

        assertEquals(RunStage.FAILED, stage);
        final RunStep.Row limbo = steps.rows().getLast();
        assertEquals(RunStep.Kind.RESOLVE, limbo.step());
        assertEquals("limbo", limbo.service());
        assertEquals(RunStep.Outcome.FAILED, limbo.outcome());
        assertEquals(RunStep.Reason.SOURCE_FAILED, limbo.reason());
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
        final RunStage stage = report(plan(PACKETEVENTS), missing -> {
                    throw new IllegalStateException("disk full");
                })
                .orElseThrow();

        assertEquals(RunStage.FAILED, stage);
        final RunStep.Row resolved = steps.rows().stream()
                .filter(row -> "smp".equals(row.service()))
                .findFirst()
                .orElseThrow();
        assertEquals(
                List.of(new RunStep.Change("packetevents", null, "2.13.0", null)),
                resolved.changes(),
                "what it meant to install stays named");
    }

    @Test
    void theInstallWritesItsStepsWithTheVersionsItMoved() {
        report(
                plan(PACKETEVENTS),
                missing -> new ApplyResult(List.of(new ApplyResult.Outcome(
                        "smp",
                        "packetevents",
                        ApplyResult.Status.DONE,
                        "packetevents-spigot-2.13.0.jar",
                        "packetevents-spigot-2.13.0.jar"))));

        final RunStep.Row install = steps.rows().getLast();
        assertEquals(RunStep.Kind.INSTALL, install.step());
        assertEquals("smp", install.service());
        assertEquals(RunStep.Outcome.DONE, install.outcome());
        assertEquals(List.of(new RunStep.Change("packetevents", null, "2.13.0", null)), install.changes());
    }

    @Test
    void anInstallThatThrowsEndsItsStepUnexpectedly() {
        report(plan(PACKETEVENTS), missing -> {
            throw new IllegalStateException("disk full");
        });

        final RunStep.Row install = steps.rows().getLast();
        assertEquals(RunStep.Kind.INSTALL, install.step());
        assertEquals(RunStep.Outcome.FAILED, install.outcome());
        assertEquals(RunStep.Reason.UNEXPECTED, install.reason());
    }

    private Optional<RunStage> report(final UpdatePlan missing, final Function<UpdatePlan, ApplyResult> apply) {
        return Bootstrap.report(new Steps(steps, 1), missing, apply);
    }

    private static UpdatePlan plan(final Change... changes) {
        return new UpdatePlan(Instant.EPOCH, "v0.20.0", false, List.of(changes), List.of(), List.of());
    }
}
