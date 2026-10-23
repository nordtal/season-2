package eu.nordtal.season.stewardagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.database.update.RunRecord;
import eu.nordtal.season.database.update.RunStage;
import eu.nordtal.season.database.update.RunStep;
import eu.nordtal.season.database.update.UpdateStatus;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** Tests that {@code status} on the host prints a run's steps and tells a kept local build from other failures. */
class HostRequestsTest {

    @Test
    void aRunThatStoppedBeforeReplacingALocalBuildIsTold() {
        assertTrue(HostRequests.keptLocal(
                UpdateStatus.FAILED, failed(step(RunStep.Kind.RESOLVE, null, RunStep.Reason.REFUSED_LOCAL_BUILD))));
    }

    @Test
    void anyOtherFailureOrNoStepIsNot() {
        final RunRecord other = failed(step(RunStep.Kind.RESOLVE, null, RunStep.Reason.OLDER_RELEASE));

        assertFalse(HostRequests.keptLocal(UpdateStatus.FAILED, other));
        assertFalse(HostRequests.keptLocal(UpdateStatus.FAILED, RunRecord.EMPTY));
        assertFalse(HostRequests.keptLocal(UpdateStatus.DONE, other));
    }

    @Test
    void theStatusPrintsEveryStepWithItsFactsBelowIt() {
        final RunRecord run = failed(
                step(RunStep.Kind.STOP, "smp", null),
                new RunStep.Row(
                        2,
                        RunStep.Kind.DUMP_DATABASE,
                        null,
                        null,
                        null,
                        RunStep.Outcome.FAILED,
                        RunStep.Reason.TOOL_FAILED,
                        2,
                        List.of(),
                        List.of(),
                        List.of(),
                        null,
                        null,
                        null,
                        List.of(),
                        List.of()));

        assertEquals(
                List.of(
                        "FAILED\tFAILED",
                        "Stop smp: done",
                        "Dump the database: failed",
                        "    the tool failed",
                        "    exit code 2"),
                HostRequests.told(UpdateStatus.FAILED, run));
    }

    private static RunRecord failed(final RunStep.Row... steps) {
        return new RunRecord(RunStage.FAILED, List.of(steps));
    }

    private static RunStep.Row step(
            final RunStep.Kind kind, final @Nullable String service, final RunStep.@Nullable Reason reason) {
        return new RunStep.Row(
                1,
                kind,
                service,
                null,
                null,
                reason == null ? RunStep.Outcome.DONE : RunStep.Outcome.FAILED,
                reason,
                null,
                List.of(),
                List.of(),
                List.of(),
                null,
                null,
                null,
                List.of(),
                List.of());
    }
}
