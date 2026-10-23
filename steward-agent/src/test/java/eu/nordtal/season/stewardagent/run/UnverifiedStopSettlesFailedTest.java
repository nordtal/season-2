package eu.nordtal.season.stewardagent.run;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.database.update.RunStage;
import eu.nordtal.season.database.update.RunStep;
import eu.nordtal.season.internalapi.agent.Topology;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests how a run that stopped servers settles.
 *
 * An unverified stop settles the run {@code FAILED}; on a restart its stop step stays the warning it is.
 */
class UnverifiedStopSettlesFailedTest {

    private static final Runner.Doubt FAILS = Runner.Doubt.FAILS_THE_RUN;
    private static final Runner.Doubt SAID = Runner.Doubt.IS_ONLY_SAID;

    private final CollectedSteps collected = new CollectedSteps();
    private final Steps record = new Steps(collected, 1);

    @Test
    void anUnverifiedStopFailsTheRunOnItsOwnWithEveryStepGreen() {
        green();

        assertEquals(
                RunStage.FAILED,
                Runner.settle(record, List.of(Topology.SMP), false, FAILS),
                "every step is green and the run is still not a success: nothing in it knows whether the world"
                        + " had finished writing when the tar ran");
    }

    @Test
    void anOrdinaryRunSettlesDone() {
        // A rule that always failed the nightly backup would be ignored.
        green();

        assertEquals(RunStage.DONE, Runner.settle(record, List.of(), false, FAILS));
    }

    @Test
    void aCallerThatHasAlreadyFailedFailsARunWhoseStepsAreAllGreen() {
        // An arrival that never came fails the run despite healthy services.
        green();

        assertEquals(RunStage.FAILED, Runner.settle(record, List.of(), true, FAILS));
    }

    @Test
    void aFailedStepFailsARunWithNoUnverifiedStops() {
        green();
        record.begin(RunStep.Kind.DUMP_DATABASE).failed(RunStep.Reason.TOOL_FAILED);

        assertEquals(RunStage.FAILED, Runner.settle(record, List.of(), false, FAILS));
        assertEquals(RunStage.FAILED, Runner.settle(record, List.of(), false, SAID));
    }

    @Test
    void aRestartIsNotFailedOverAnUnverifiedStop() {
        green();

        assertEquals(
                RunStage.DONE,
                Runner.settle(record, List.of(Topology.SMP), false, SAID),
                "a restart leaves nothing behind that anybody has to decide whether to trust");
        assertEquals(RunStage.FAILED, Runner.settle(record, List.of(Topology.SMP), true, SAID));
    }

    /** Steps in which everything went right, the stop of the SMP a warning as an unverified stop writes it. */
    private void green() {
        record.begin(RunStep.Kind.STOP, Topology.SMP).warned(RunStep.Reason.UNVERIFIED_STOP);
        record.begin(RunStep.Kind.SAVE_VOLUME, "mc-smp").done();
        record.begin(RunStep.Kind.VERIFY, Topology.SMP).done();
    }
}
