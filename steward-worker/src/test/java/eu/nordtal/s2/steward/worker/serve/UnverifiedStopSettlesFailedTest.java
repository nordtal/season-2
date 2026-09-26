package eu.nordtal.s2.steward.worker.serve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.common.update.UpdateReport;
import eu.nordtal.s2.steward.worker.backup.DatabaseDump;
import eu.nordtal.s2.steward.worker.plan.Topology;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * How a run that stopped servers is settled, and the one rule in it that is not about a failed line.
 *
 * <h2>The rule</h2>
 * Every line can be green - the service stopped, the volume saved, the jars moved, the servers came
 * back - and the one thing missing is the evidence that the server had finished writing when the
 * next step touched its files. Owner's decision, 2026-09-13: that settles the run {@code FAILED} on
 * both paths, because an archive nobody can vouch for is the irreversible thing a
 * green-looking run would otherwise unlock.
 *
 * <h2>Why the note is asserted on the same object as the stage, every time</h2>
 * There are three possible outcomes here and only one of them is right. {@code DONE} over an
 * unverified stop is run 23. {@code FAILED} <em>with the note dropped</em> is worse than either,
 * because it is a failure with no reason attached: somebody reads FAILED on a backup run, assumes
 * nothing was saved, and goes looking for an older archive - while the night's archives sit on the
 * disk with a mark beside them that nobody has been told to read. So no test here checks a stage
 * without also checking what the report says about it.
 *
 * <h2>Three conditions, or-ed, and each one is load-bearing on its own</h2>
 * An unverified stop, a caller that has already failed ({@code result.hasFailures()} on the update
 * path - a download that did not arrive), and a line that failed. Folding three conditions into one
 * expression is exactly where one of them quietly stops being read, so each is driven here with the
 * other two switched off.
 *
 * <h2>And two modes, which is a fourth way for them to interact</h2>
 * {@link Runner.Doubt#IS_ONLY_SAID} is the restart path: nothing was written between the stop and
 * the start, so there is no artefact anybody later has to decide whether to trust, and the run is
 * not failed over it. What it is <b>not</b> is silence - a restart is what somebody does when a
 * server is already misbehaving, which is exactly when a stop nobody could read is most likely, and
 * the server was started again on a world that may have been cut off mid-save. So the note is made
 * either way, and the only difference is what the last sentence of it says. Two things follow, and
 * both are held below: a mode that quietly drops the note is the failure this whole design is
 * about, and a mode that swallows the <em>other</em> two conditions would turn every restart into a
 * success. The second is one parenthesis away.
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

    // ---------------------------------------------------------------- the unverified stop

    @Test
    @DisplayName("an unverified stop fails the run on its own, with every line green")
    void anUnverifiedStopIsEnoughByItself() {
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
    @DisplayName("the note is on the report that carries the stage, not on one left behind")
    void theNoteTravelsWithTheStage() {
        // The worst of the three outcomes: a run reported FAILED with the reason dropped on the
        // floor. It reads as "the backup did not happen", which is the one thing that is not true.
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
    @DisplayName("the note names every service whose ending was unread, not just the first")
    void everyUnverifiedServiceIsNamed() {
        final UpdateReport settled =
                Runner.settle(green(), List.of(Topology.SMP, Topology.LIMBO), ARCHIVES, false, FAILS);

        assertTrue(
                note(settled).contains(Topology.SMP) && note(settled).contains(Topology.LIMBO),
                "two servers were stopped without anybody seeing how, and a note naming one of them"
                        + " sends an admin to look at half the problem: " + note(settled));
    }

    @Test
    @DisplayName("the sentence about what is at risk is the caller's, and it is really used")
    void theRiskSentenceComesFromTheCaller() {
        // The two paths are in doubt about different things. A settle that ignored this parameter
        // would tell an update run to go and look at archives it never wrote - which is an
        // instruction that wastes the one hour somebody has at four in the morning.
        final UpdateReport backup = Runner.settle(green(), List.of(Topology.SMP), ARCHIVES, false, FAILS);
        final UpdateReport update = Runner.settle(green(), List.of(Topology.SMP), JARS, false, FAILS);

        assertTrue(note(backup).contains("restore.sh --list"), note(backup));
        assertTrue(note(update).contains("plugins directory"), note(update));
        assertFalse(
                note(update).contains("restore.sh"),
                "the update path did not write an archive and must not be sent to look for one: " + note(update));
    }

    @Test
    @DisplayName("notes the run already made survive being settled")
    void theEarlierNotesAreKept() {
        // The backup path writes its retention line before this is reached - "kept the newest 7 of
        // each and removed 3: ...". A settle that built a fresh report instead of adding to the one
        // it was given would delete the only record there is of what was deleted from the disk.
        final UpdateReport swept = green().withNote("kept the newest 7 of each and removed 3");

        final UpdateReport settled = Runner.settle(swept, List.of(Topology.SMP), ARCHIVES, false, FAILS);

        assertEquals(2, settled.notes().size(), settled.notes().toString());
        assertTrue(
                settled.notes().getFirst().startsWith("kept the newest"),
                "the retention line is gone, and it is the only record of what was deleted: " + settled.notes());
    }

    // ---------------------------------------------------------------- the ordinary night

    @Test
    @DisplayName("an ordinary run settles DONE with nothing added to it")
    void nothingWrongIsDone() {
        // The other half of the claim, and the expensive half to get wrong: this is the nightly
        // backup. A rule that failed it would fail the run every night instead of on the
        // night it matters, and a warning that is always on is one people stop reading.
        final UpdateReport settled = Runner.settle(green(), List.of(), ARCHIVES, false, FAILS);

        assertEquals(UpdateReport.Stage.DONE, settled.stage());
        assertEquals(List.of(), settled.notes(), "a good run's report says what it did and adds no warnings to it");
    }

    // ---------------------------------------------------------------- the other two conditions

    @Test
    @DisplayName("a caller that has already failed still fails a run whose lines are all green")
    void alreadyFailedSurvivesTheFolding() {
        // This is result.hasFailures() on the update path: a download that never arrived fails the
        // run even though every service stopped, started and came back healthy. It used to be
        // or-ed at the call site; folding it in here is exactly where a condition gets lost,
        // because nothing else in the report shows it.
        final UpdateReport settled = Runner.settle(green(), List.of(), JARS, true, FAILS);

        assertEquals(
                UpdateReport.Stage.FAILED,
                settled.stage(),
                "an artefact that could not be downloaded is a failed update, and the report's"
                        + " lines are all about services rather than about downloads");
    }

    @Test
    @DisplayName("and it does not invent an unverified stop to explain itself")
    void alreadyFailedAddsNoNote() {
        // The note belongs to the unverified stops and to nothing else. Or-ed into the note's
        // condition by mistake, a failed download would publish "UNVERIFIED STOP.  stopped, and
        // how it ended could not be read back" - a warning naming nobody, about a thing that did
        // not happen, on the run where somebody is already trying to work out what went wrong.
        final UpdateReport settled = Runner.settle(green(), List.of(), JARS, true, FAILS);

        assertEquals(List.of(), settled.notes(), settled.notes().toString());
    }

    @Test
    @DisplayName("a failed line still fails a run with no unverified stops")
    void aFailedLineIsStillAFailure() {
        // The condition that was here before any of this, and the one most likely to be dropped
        // while rearranging the other two: the database dump that did not run is a FAILED line and
        // nothing else in the call says so.
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
    @DisplayName("the stage is always decided, never left where the run had got to")
    void theStageIsAlwaysSet() {
        // What arrives here is a report at VERIFYING. A settle that only set the stage on one of
        // its branches would publish a finished run still saying "Waiting for the servers to come
        // back", which is how a row looks when the worker died mid-run.
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

    // ---------------------------------------------------------------- the restart's half

    @Test
    @DisplayName("a restart is not failed over an unverified stop, and is still told about it")
    void saidButNotFailed() {
        // Both halves on one returned object, because either alone is a defect that reads as
        // working. DONE with no note is the silence the owner ruled against; a note on a run that
        // was failed anyway would be the rule the restart path was deliberately kept out of.
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
    @DisplayName("the two modes end their note differently, and neither borrows the other's ending")
    void eachModeOwnsItsLastSentence() {
        // A note that tells somebody the run was reported FAILED when it was not is worse than no
        // note: it sends them to look for a failure that is not in the row, and the next time they
        // see the words they will not believe them.
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
    @DisplayName("the mode says nothing about the other two conditions - a failed line still fails")
    void theModeDoesNotSwallowAFailedLine() {
        // One parenthesis. `doubt == FAILS_THE_RUN && (unverified || alreadyFailed || lines)`
        // compiles, reads almost the same, and makes every restart a success no matter what
        // happened in it - including the one where a server never came back.
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
    @DisplayName("and a caller that had already failed still fails under the quieter mode")
    void theModeDoesNotSwallowAnAlreadyFailedRun() {
        final UpdateReport settled = Runner.settle(green(), List.of(Topology.SMP), SAME_WORLD, true, SAID);

        assertEquals(UpdateReport.Stage.FAILED, settled.stage());
        assertTrue(
                note(settled).contains("not reported as a failure over it"),
                "the run IS a failure, for another reason, and the note has to keep saying 'over"
                        + " it' or it contradicts the stage printed above it: " + note(settled));
    }

    @Test
    @DisplayName("an ordinary restart settles DONE with nothing added to it")
    void anOrdinaryRestartSaysNothing() {
        // Which is nearly every restart. A note on all of them is a note on none of them.
        final UpdateReport settled = Runner.settle(green(), List.of(), SAME_WORLD, false, SAID);

        assertEquals(UpdateReport.Stage.DONE, settled.stage());
        assertEquals(List.of(), settled.notes());
    }

    // ---------------------------------------------------------------- helpers

    /**
     * A report in which everything went right: the volume saved, both servers back.
     *
     * <p>At {@code VERIFYING}, because that is the stage a report is really at when it reaches
     * {@code settle} - the run has not decided anything yet.</p>
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
