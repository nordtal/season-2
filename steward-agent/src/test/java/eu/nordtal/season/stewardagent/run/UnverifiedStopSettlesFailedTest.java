package eu.nordtal.season.stewardagent.run;

import static eu.nordtal.season.database.AdminTexts.TEXTS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.database.update.UpdateReport;
import eu.nordtal.season.internalapi.agent.Topology;
import eu.nordtal.season.stewardagent.Told;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * How a run that stopped servers is settled, and the one rule in it that is not about a failed line.
 *
 * An unverified stop settles the run {@code FAILED} with a failed record; on a restart the record is a warning.
 */
class UnverifiedStopSettlesFailedTest {

    private static final Runner.Doubt FAILS = Runner.Doubt.FAILS_THE_RUN;
    private static final Runner.Doubt SAID = Runner.Doubt.IS_ONLY_SAID;

    private static final String UNREAD = "stopped, but how it ended could not be read back";

    @Test
    void anUnverifiedStopFailsTheRunOnItsOwnWithEveryLineGreen() {
        final UpdateReport settled = Runner.settle(green(), List.of(Topology.SMP), false, FAILS);

        assertEquals(
                UpdateReport.Stage.FAILED,
                settled.stage(),
                "every line of this report is green and the run is still not a success: nothing in"
                        + " it knows whether the world had finished writing when the tar ran");
        assertEquals(
                List.of("STOP FAILED " + Topology.SMP + ": " + UNREAD),
                Told.notes(settled),
                "one failed record on the report that carries the stage, naming the server");
    }

    @Test
    void everyServiceWhoseEndingWasUnreadHasItsOwnRecord() {
        final UpdateReport settled = Runner.settle(green(), List.of(Topology.SMP, Topology.LIMBO), false, FAILS);

        assertEquals(
                List.of("STOP FAILED " + Topology.SMP + ": " + UNREAD, "STOP FAILED " + Topology.LIMBO + ": " + UNREAD),
                Told.notes(settled),
                "a record naming one of two servers sends an admin to look at half the problem");
    }

    @Test
    void notesTheRunAlreadyMadeSurviveBeingSettled() {
        // The backup path writes its retention line before this is reached; a fresh report here would lose it.
        final UpdateReport swept = green().withNote(UpdateReport.Note.done(
                UpdateReport.Step.BACKUP, TEXTS.report().words("kept the newest 7 of each and removed 3")));

        final UpdateReport settled = Runner.settle(swept, List.of(Topology.SMP), false, FAILS);

        assertEquals(
                List.of(
                        "BACKUP DONE -: kept the newest 7 of each and removed 3",
                        "STOP FAILED " + Topology.SMP + ": " + UNREAD),
                Told.notes(settled),
                "the retention record is the only one of what was deleted");
    }

    @Test
    void anOrdinaryRunSettlesDoneWithNothingAddedToIt() {
        // The expensive half to get wrong: a rule that always failed the nightly backup gets ignored by everyone.
        final UpdateReport settled = Runner.settle(green(), List.of(), false, FAILS);

        assertEquals(UpdateReport.Stage.DONE, settled.stage());
        assertEquals(List.of(), settled.notes(), "a good run's report says what it did and adds no warnings to it");
    }

    @Test
    void aCallerThatHasAlreadyFailedFailsARunWhoseLinesAreAllGreenAndInventsNoRecord() {
        // result.hasFailures() on the update path: an arrival that never came fails the run despite healthy services.
        final UpdateReport settled = Runner.settle(green(), List.of(), true, FAILS);

        assertEquals(UpdateReport.Stage.FAILED, settled.stage());
        assertEquals(List.of(), settled.notes(), "the record belongs to the unverified stops and nothing else");
    }

    @Test
    void aFailedLineStillFailsARunWithNoUnverifiedStops() {
        // Most likely to be dropped while rearranging the other two: a dump that did not run is a FAILED line.
        final UpdateReport report = green().with(new UpdateReport.ServiceLine(
                Snapshots.DATABASE,
                UpdateReport.State.FAILED,
                List.of(),
                TEXTS.report().words("pg_dump exited 1")));

        final UpdateReport settled = Runner.settle(report, List.of(), false, FAILS);

        assertEquals(UpdateReport.Stage.FAILED, settled.stage());
        assertEquals(List.of(), settled.notes(), "the failure explains itself on its own line");
    }

    @Test
    void theStageIsAlwaysDecidedNeverLeftWhereTheRunHadGotTo() {
        // What arrives here is a report at VERIFYING; setting the stage on only one branch would leave it stuck there.
        assertEquals(UpdateReport.Stage.VERIFYING, green().stage(), "the fixture is the input shape");
        assertTrue(Runner.settle(green(), List.of(), false, FAILS).stage().isFinished());
        assertTrue(Runner.settle(green(), List.of(Topology.SMP), false, FAILS)
                .stage()
                .isFinished());
        assertTrue(Runner.settle(green(), List.of(), true, FAILS).stage().isFinished());
    }

    @Test
    void aRestartIsNotFailedOverAnUnverifiedStopAndIsStillWarnedAboutIt() {
        // Both halves on one returned object; either alone is a defect that reads as working.
        final UpdateReport settled = Runner.settle(green(), List.of(Topology.SMP), false, SAID);

        assertEquals(
                UpdateReport.Stage.DONE,
                settled.stage(),
                "a restart leaves nothing behind that anybody has to decide whether to trust");
        assertEquals(
                List.of("STOP WARNING " + Topology.SMP + ": " + UNREAD),
                Told.notes(settled),
                "and it is still said, as a warning: the server may have been killed mid-save");
    }

    @Test
    void theQuieterModeStillFailsOnAFailedLineAndKeepsItsRecordAWarning() {
        // One parenthesis: a misplaced one here compiles, reads almost the same, and makes every restart a success.
        final UpdateReport report = green().with(new UpdateReport.ServiceLine(
                Topology.LIMBO,
                UpdateReport.State.FAILED,
                List.of(),
                TEXTS.report().words("did not come back within 5 minutes")));

        final UpdateReport settled = Runner.settle(report, List.of(Topology.SMP), false, SAID);
        final UpdateReport failed = Runner.settle(green(), List.of(Topology.SMP), true, SAID);

        assertEquals(UpdateReport.Stage.FAILED, settled.stage(), "limbo did not come back");
        assertEquals(UpdateReport.Stage.FAILED, failed.stage());
        assertEquals(
                List.of("STOP WARNING " + Topology.SMP + ": " + UNREAD),
                Told.notes(failed),
                "the run failed for another reason, so the stop's record must not claim the failure");
    }

    @Test
    void anOrdinaryRestartSettlesDoneWithNothingAddedToIt() {
        // Which is nearly every restart. A note on all of them is a note on none of them.
        final UpdateReport settled = Runner.settle(green(), List.of(), false, SAID);

        assertEquals(UpdateReport.Stage.DONE, settled.stage());
        assertEquals(List.of(), settled.notes());
    }

    /** A report in which everything went right, at the {@code VERIFYING} stage {@code settle} receives. */
    private static UpdateReport green() {
        return UpdateReport.at(UpdateReport.Stage.VERIFYING)
                .with(new UpdateReport.ServiceLine(
                        "mc-smp",
                        UpdateReport.State.SAVED,
                        List.of(new UpdateReport.Change("backup", null, "saved 1.2 GiB in 12s")),
                        null))
                .with(new UpdateReport.ServiceLine(Topology.SMP, UpdateReport.State.HEALTHY, List.of(), null))
                .with(new UpdateReport.ServiceLine(Topology.LIMBO, UpdateReport.State.HEALTHY, List.of(), null));
    }
}
