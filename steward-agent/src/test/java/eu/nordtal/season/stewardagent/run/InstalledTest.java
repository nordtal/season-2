package eu.nordtal.season.stewardagent.run;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.database.DatabaseText;
import eu.nordtal.season.database.update.RunLines;
import eu.nordtal.season.database.update.RunStep;
import eu.nordtal.season.stewardagent.apply.ApplyResult;
import eu.nordtal.season.stewardagent.plan.UpdatePlan;
import eu.nordtal.season.stewardagent.plan.Work;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class InstalledTest {

    @Test
    void anInstallNamesWhatItChangedAndAPluginTheReleaseRetiredAsRemoved() {
        final UpdatePlan plan = new UpdatePlan(
                Instant.EPOCH,
                null,
                false,
                List.of(),
                List.of(),
                List.of(),
                List.of(new UpdatePlan.Retiring(
                        "smp", "coreprotect", List.of("CoreProtect-CE-24.1.jar", "CoreProtect"))));
        final Work planned = Work.of(plan).with("smp", new RunStep.Change("paper", "133", "134", null));
        final ApplyResult result = new ApplyResult(List.of(
                new ApplyResult.Outcome("smp", "paper", ApplyResult.Status.DONE, null, null),
                new ApplyResult.Outcome("smp", "voicechat", ApplyResult.Status.UNCHANGED, null, null),
                new ApplyResult.Outcome("smp", "chunky", ApplyResult.Status.UNSUPPORTED, null, null),
                new ApplyResult.Outcome(null, "resource-pack", ApplyResult.Status.UNCHANGED, null, null),
                new ApplyResult.Outcome("hunger-games", "voicechat", ApplyResult.Status.UNCHANGED, null, null),
                new ApplyResult.Outcome(
                        "smp", "coreprotect", ApplyResult.Status.DONE, "removed CoreProtect-CE-24.1.jar", null)));
        final CollectedSteps collected = new CollectedSteps();

        Installed.record(new Steps(collected, 1), planned, result, List.of("smp", "hunger-games"));

        assertEquals(1, collected.rows().size(), "a server or pack that changed nothing has no install step");
        final RunStep.Row install = collected.rows().getFirst();
        assertEquals(
                List.of(
                        new RunStep.Change("paper", "133", "134", null),
                        new RunStep.Change("coreprotect", "24.1", null, null)),
                install.changes(),
                "an artefact already right, or without a build, is no change of the install");
        assertEquals(
                "coreprotect 24.1, removed",
                DatabaseText.english(RunLines.change(install.changes().getLast())),
                "a retired plugin reads as removed, not as installed");
    }
}
