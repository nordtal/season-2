package eu.nordtal.s2.steward.worker.serve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.common.update.UpdateReport;
import eu.nordtal.s2.steward.worker.backup.DatabaseDump;
import eu.nordtal.s2.steward.worker.plan.Topology;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * How a run that stopped servers is settled, and the one rule in it that is not about a failed line.
 *
 * The rule: Every line can be green - the service stopped, the volume saved, the jars moved, the servers came back -
 * and the one thing missing is the evidence that the server had finished writing when the next step touched its
 * files. Owner's decision: that settles the run {@code FAILED} on both paths, because an archive nobody
 * can vouch for is the irreversible thing a green-looking run would otherwise unlock.
 *
 * Why the note is asserted on the same object as the stage, every time: There are three possible outcomes here and
 * only one of them is right. {@code DONE} over an unverified stop is run 23. {@code FAILED} with the note dropped is
 * worse than either, because it is a failure with no reason attached: somebody reads FAILED on a backup run, assumes
 * nothing was saved, and goes looking for an older archive - while the night's archives sit on the disk with a mark
 * beside them that nobody has been told to read. So no test here checks a stage without also checking what the
 * report says about it.
 *
 * Three conditions, or-ed, and each one is load-bearing on its own: An unverified stop, a caller that has already
 * failed ( {@code result.hasFailures()} on the update path - a download that did not arrive), and a line that
 * failed. Folding three conditions into one expression is exactly where one of them quietly stops being read, so
 * each is driven here with the other two switched off.
 *
 * And two modes, which is a fourth way for them to interact: {@link Runner.Doubt#IS_ONLY_SAID} is the restart path:
 * nothing was written between the stop and the start, so there is no artefact anybody later has to decide whether to
 * trust, and the run is not failed over it. What it is not is silence - a restart is what somebody does when a
 * server is already misbehaving, which is exactly when a stop nobody could read is most likely, and the server was
 * started again on a world that may have been cut off mid-save. So the note is made either way, and the only
 * difference is what the last sentence of it says. Two things follow, and both are held below: a mode that quietly
 * drops the note is the failure this whole design is about, and a mode that swallows the other two conditions would
 * turn every restart into a success. The second is one parenthesis away.
 */
class UnverifiedStopSettlesFailedTest {

    private static final Runner.Doubt FAILS = Runner.Doubt.FAILS_THE_RUN;
    private static final Runner.Doubt SAID = Runner.Doubt.IS_ONLY_SAID;

    /** What the backup path passes: the sentence naming what is now in doubt. */
    private static final String ARCHIVES = "the archives were taken - they were kept, and each one"
            + " has a .unverified file beside it saying so, which `deploy/restore.sh --list` prints";

    /** And what the update path passes, which is a different sentence for a different risk. */
    private static final String JARS = "the jars were moved into its plugins directory";

    /** The restart path's, which is the argument for saying anything at all put into a sentence. */
    private static final String SAME_WORLD = "it was started again on the same world";

    @Test
    void anUnverifiedStopFailsTheRunOnItsOwnWithEveryLineGreen() {
        final UpdateReport settled = Runner.settle(green(), List.of(Topology.SMP), ARCHIVES, false, FAILS);

        assertEquals(
                UpdateReport.Stage.FAILED,
                settled.stage(),
                "every line of this report is green and the run is still not a success: nothing in"
                        + " it knows whether the world had finished writing when the tar ran");
        assertTrue(
                note(settled).contains(Topology.SMP),
                "and the note has to name the server, so somebody can go and read that server's own"
                        + " log rather than all four: " + note(settled));
    }

    @Test
    void theNoteIsOnTheReportThatCarriesTheStageNotOnOneLeftBehind() {
        // The worst outcome: a run reported FAILED with the reason dropped, reading as "the backup did not happen".
        final UpdateReport settled = Runner.settle(green(), List.of(Topology.SMP), ARCHIVES, false, FAILS);

        assertEquals(UpdateReport.Stage.FAILED, settled.stage());
        assertEquals(
                1, settled.notes().size(), "exactly one note, on the one report that is published: " + settled.notes());
        assertTrue(
                note(settled).startsWith("UNVERIFIED STOP."),
                "the first words decide whether anybody reads the rest: " + note(settled));
        assertTrue(
                note(settled).contains("Nothing was thrown away and nothing was undone"),
                "without this sentence a FAILED backup reads as a night with nothing saved, and the"
                        + " next person restores from an older archive: " + note(settled));
        assertTrue(
                note(settled).contains("nothing counts this archive as one"),
                "and it has to say what failing costs, or somebody spends the morning looking for a"
                        + " reset that was never going to run: " + note(settled));
    }

    @Test
    void theNoteNamesEveryServiceWhoseEndingWasUnreadNotJustTheFirst() {
        final UpdateReport settled =
                Runner.settle(green(), List.of(Topology.SMP, Topology.LIMBO), ARCHIVES, false, FAILS);

        assertTrue(
                note(settled).contains(Topology.SMP) && note(settled).contains(Topology.LIMBO),
                "two servers were stopped without anybody seeing how, and a note naming one of them"
                        + " sends an admin to look at half the problem: " + note(settled));
    }

    @Test
    void theSentenceAboutWhatIsAtRiskIsTheCallersAndItIsReallyUsed() {
        // The two paths are in doubt about different things; ignoring it sends a run to look at archives it lacks.
        final UpdateReport backup = Runner.settle(green(), List.of(Topology.SMP), ARCHIVES, false, FAILS);
        final UpdateReport update = Runner.settle(green(), List.of(Topology.SMP), JARS, false, FAILS);

        assertTrue(note(backup).contains("restore.sh --list"), note(backup));
        assertTrue(note(update).contains("plugins directory"), note(update));
        assertFalse(
                note(update).contains("restore.sh"),
                "the update path did not write an archive and must not be sent to look for one: " + note(update));
    }

    @Test
    void notesTheRunAlreadyMadeSurviveBeingSettled() {
        // The backup path writes its retention line before this is reached; a fresh report here would lose it.
        final UpdateReport swept = green().withNote("kept the newest 7 of each and removed 3");

        final UpdateReport settled = Runner.settle(swept, List.of(Topology.SMP), ARCHIVES, false, FAILS);

        assertEquals(2, settled.notes().size(), settled.notes().toString());
        assertTrue(
                settled.notes().getFirst().startsWith("kept the newest"),
                "the retention line is gone, and it is the only record of what was deleted: " + settled.notes());
    }

    @Test
    void anOrdinaryRunSettlesDoneWithNothingAddedToIt() {
        // The expensive half to get wrong: a rule that always failed the nightly backup gets ignored by everyone.
        final UpdateReport settled = Runner.settle(green(), List.of(), ARCHIVES, false, FAILS);

        assertEquals(UpdateReport.Stage.DONE, settled.stage());
        assertEquals(List.of(), settled.notes(), "a good run's report says what it did and adds no warnings to it");
    }

    @Test
    void aCallerThatHasAlreadyFailedStillFailsARunWhoseLinesAreAllGreen() {
        // result.hasFailures() on the update path: an arrival that never came fails the run despite healthy services.
        final UpdateReport settled = Runner.settle(green(), List.of(), JARS, true, FAILS);

        assertEquals(
                UpdateReport.Stage.FAILED,
                settled.stage(),
                "an artefact that could not be downloaded is a failed update, and the report's"
                        + " lines are all about services rather than about downloads");
    }

    @Test
    void andItDoesNotInventAnUnverifiedStopToExplainItself() {
        // The note belongs to the unverified stops and nothing else; or-ed in by mistake it would name nobody.
        final UpdateReport settled = Runner.settle(green(), List.of(), JARS, true, FAILS);

        assertEquals(List.of(), settled.notes(), settled.notes().toString());
    }

    @Test
    void aFailedLineStillFailsARunWithNoUnverifiedStops() {
        // Most likely to be dropped while rearranging the other two: a dump that did not run is a FAILED line.
        final UpdateReport report = green().with(new UpdateReport.ServiceLine(
                DatabaseDump.NAME, UpdateReport.State.FAILED, List.of(), "pg_dump exited 1"));

        final UpdateReport settled = Runner.settle(report, List.of(), ARCHIVES, false, FAILS);

        assertEquals(UpdateReport.Stage.FAILED, settled.stage());
        assertEquals(
                List.of(),
                settled.notes(),
                "and the failure explains itself on its own line, so no note is added over it");
    }

    @Test
    void theStageIsAlwaysDecidedNeverLeftWhereTheRunHadGotTo() {
        // What arrives here is a report at VERIFYING; setting the stage on only one branch would leave it stuck there.
        assertEquals(UpdateReport.Stage.VERIFYING, green().stage(), "the fixture is the input shape");
        assertTrue(Runner.settle(green(), List.of(), ARCHIVES, false, FAILS)
                .stage()
                .isFinished());
        assertTrue(Runner.settle(green(), List.of(Topology.SMP), ARCHIVES, false, FAILS)
                .stage()
                .isFinished());
        assertTrue(
                Runner.settle(green(), List.of(), ARCHIVES, true, FAILS).stage().isFinished());
    }

    @Test
    void aRestartIsNotFailedOverAnUnverifiedStopAndIsStillToldAboutIt() {
        // Both halves on one returned object; either alone is a defect that reads as working.
        final UpdateReport settled = Runner.settle(green(), List.of(Topology.SMP), SAME_WORLD, false, SAID);

        assertEquals(
                UpdateReport.Stage.DONE,
                settled.stage(),
                "a restart leaves nothing behind that anybody has to decide whether to trust, so"
                        + " failing it would condemn a run that wrote nothing");
        assertTrue(
                note(settled).startsWith("UNVERIFIED STOP."),
                "and it is still said: the server may have been killed mid-save and started again"
                        + " on that world, and this is the run somebody asked for BECAUSE it was"
                        + " already misbehaving");
        assertTrue(note(settled).contains(Topology.SMP), note(settled));
        assertTrue(
                note(settled).contains("started again on the same world"),
                "the restart's own sentence, not the archive one: " + note(settled));
    }

    @Test
    void theTwoModesEndTheirNoteDifferentlyAndNeitherBorrowsTheOthersEnding() {
        // A note claiming FAILED when it was not is worse than no note: it sends somebody looking for a phantom.
        final UpdateReport failing = Runner.settle(green(), List.of(Topology.SMP), ARCHIVES, false, FAILS);
        final UpdateReport saying = Runner.settle(green(), List.of(Topology.SMP), SAME_WORLD, false, SAID);

        assertTrue(note(failing).contains("reported as FAILED for that reason alone"), note(failing));
        assertTrue(note(failing).contains("nothing counts this archive as one"), note(failing));
        assertFalse(
                note(failing).contains("not reported as a failure"),
                "the failing mode must not also claim it did not fail: " + note(failing));

        assertTrue(note(saying).contains("not reported as a failure over it"), note(saying));
        assertTrue(note(saying).contains("left nothing behind"), note(saying));
        assertFalse(
                note(saying).contains("reported as FAILED"),
                "this run settled DONE. A note saying it was reported FAILED would send somebody"
                        + " looking for a failure that is not in the row: " + note(saying));
        assertFalse(
                note(saying).contains("nothing counts this archive as one"),
                "and nothing was blocked, so promising a consequence that did not happen is the"
                        + " same lie in the other direction: " + note(saying));
    }

    @Test
    void theModeSaysNothingAboutTheOtherTwoConditionsAFailedLineStillFails() {
        // One parenthesis: a misplaced one here compiles, reads almost the same, and makes every restart a success.
        final UpdateReport report = green().with(new UpdateReport.ServiceLine(
                Topology.LIMBO, UpdateReport.State.FAILED, List.of(), "did not come back within 5 minutes"));

        final UpdateReport settled = Runner.settle(report, List.of(Topology.SMP), SAME_WORLD, false, SAID);

        assertEquals(
                UpdateReport.Stage.FAILED,
                settled.stage(),
                "limbo did not come back. That is a failed restart whatever an unverified stop"
                        + " does or does not cost on this path");
    }

    @Test
    void andACallerThatHadAlreadyFailedStillFailsUnderTheQuieterMode() {
        final UpdateReport settled = Runner.settle(green(), List.of(Topology.SMP), SAME_WORLD, true, SAID);

        assertEquals(UpdateReport.Stage.FAILED, settled.stage());
        assertTrue(
                note(settled).contains("not reported as a failure over it"),
                "the run IS a failure, for another reason, and the note has to keep saying 'over"
                        + " it' or it contradicts the stage printed above it: " + note(settled));
    }

    @Test
    void anOrdinaryRestartSettlesDoneWithNothingAddedToIt() {
        // Which is nearly every restart. A note on all of them is a note on none of them.
        final UpdateReport settled = Runner.settle(green(), List.of(), SAME_WORLD, false, SAID);

        assertEquals(UpdateReport.Stage.DONE, settled.stage());
        assertEquals(List.of(), settled.notes());
    }

    /**
     * A report in which everything went right: the volume saved, both servers back.
     *
     * At {@code VERIFYING}, because that is the stage a report is really at when it reaches {@code settle} - the
     * run has not decided anything yet.
     */
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

    /** The one note a settled report has, or a failure saying there was none. */
    private static String note(final UpdateReport settled) {
        assertFalse(settled.notes().isEmpty(), "no note was added at all, so the run is a failure nobody can explain");
        return settled.notes().getLast();
    }
}
