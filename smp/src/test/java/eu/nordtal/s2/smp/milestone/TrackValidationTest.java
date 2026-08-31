package eu.nordtal.s2.smp.milestone;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rule that decides whether a reloaded milestone file may replace the running one.
 *
 * <p>It has two halves that pull against each other, and the whole value of this test is that both
 * are asserted: it must <b>refuse</b> a change that orphans stored progress, and it must
 * <b>permit</b> lowering the target of a live objective. A validation that only did the first would
 * look correct and would quietly delete the first and finest of docs/smp.md's three escape hatches -
 * which is a failure that would not surface until the day somebody needed it.
 */
class TrackValidationTest {

    private static final Objective GATE = new Objective("gate", ObjectiveType.ADVANCEMENT,
            "participation", 10, List.of(), "", List.of(), "minecraft:story/iron_tools");
    private static final Objective LOGS = new Objective("logs", ObjectiveType.HAND_IN,
            "gathering", 2048, List.of("OAK_LOG"), "", List.of(), "");

    private final MilestoneTrack track = new MilestoneTrack(List.of(
            new Milestone("waiting", Unlock.BORDER, 20, 0, false, List.of()),
            new Milestone("foothold", Unlock.BORDER, 99, 30, false, List.of(LOGS, GATE))));

    // ---------------------------------------------------------------- the escape hatch

    @Test
    void loweringTheTargetOfALiveObjectiveIsAllowed() {
        // THE POINT OF THIS WHOLE CLASS. docs/smp.md#when-an-objective-turns-out-to-be-impossible:
        // "it must explicitly permit changing the target of a live objective, or the first and
        // finest escape hatch does not exist at the config level and every rescue becomes an admin
        // command" - which pays proportionally rather than in full.
        final StoredProgress stored = progress(MilestoneState.ACTIVE,
                objective("logs", ObjectiveType.HAND_IN, 1500, 2048, false));

        assertTrue(TrackValidation.validate(lowered("logs", 1000), stored).isEmpty(),
                "lowering a live target must not be refused");
    }

    @Test
    void raisingTheTargetOfALiveObjectiveIsAlsoAllowed() {
        // The same edit in the other direction. Refusing it would mean a typo could only ever be
        // corrected downwards.
        final StoredProgress stored = progress(MilestoneState.ACTIVE,
                objective("logs", ObjectiveType.HAND_IN, 100, 2048, false));

        assertTrue(TrackValidation.validate(lowered("logs", 4096), stored).isEmpty());
    }

    @Test
    void loweringATargetBelowTheCollectedProgressCompletesTheObjective() {
        // The other half of the same hatch, and it lives in ObjectiveProgress rather than here:
        // validation lets the change through, and the engine notices the objective is already done.
        assertTrue(ObjectiveProgress.completesOnReload(1500, 1000));
        assertFalse(ObjectiveProgress.completesOnReload(900, 1000));
    }

    // ---------------------------------------------------------------- orphaning

    @Test
    void aRenamedMilestoneIsRefused() {
        // From here a rename looks like a deletion, which is exactly the point: the progress and
        // any aura already paid against it would have nothing to point at.
        final StoredProgress stored = progress(MilestoneState.ACTIVE,
                objective("logs", ObjectiveType.HAND_IN, 100, 2048, false));
        final MilestoneTrack renamed = new MilestoneTrack(List.of(
                new Milestone("waiting", Unlock.BORDER, 20, 0, false, List.of()),
                new Milestone("first-steps", Unlock.BORDER, 99, 30, false, List.of(LOGS, GATE))));

        final List<TrackValidation.Problem> problems = TrackValidation.validate(renamed, stored);

        assertEquals(1, problems.size(), problems.toString());
        assertEquals("foothold", problems.get(0).milestoneKey());
    }

    @Test
    void aDeletedObjectiveWithProgressIsRefused() {
        final StoredProgress stored = progress(MilestoneState.ACTIVE,
                objective("logs", ObjectiveType.HAND_IN, 100, 2048, false));
        final MilestoneTrack without = new MilestoneTrack(List.of(
                new Milestone("waiting", Unlock.BORDER, 20, 0, false, List.of()),
                new Milestone("foothold", Unlock.BORDER, 99, 30, false, List.of(GATE))));

        final List<TrackValidation.Problem> problems = TrackValidation.validate(without, stored);

        assertEquals(1, problems.size(), problems.toString());
        assertEquals("logs", problems.get(0).objectiveKey());
    }

    @Test
    void changingAnObjectivesTypeIsRefusedOnceItHasProgress() {
        // `amount` means a different thing per type - items delivered, a statistic's increase, a
        // count of distinct players - so carrying it across is reading a number in the wrong unit.
        final StoredProgress stored = progress(MilestoneState.ACTIVE,
                objective("logs", ObjectiveType.STATISTIC, 100, 2048, false));

        final List<TrackValidation.Problem> problems = TrackValidation.validate(track, stored);

        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.get(0).message().contains("changed type"), problems.toString());
    }

    @Test
    void changingTheTargetOfACompletedObjectiveIsRefused() {
        // It has already paid out, and an admin completion's pot × (reached ÷ target) refers to
        // what was asked for at the time. Moving it afterwards rewrites the arithmetic behind aura
        // that is already in the ledger.
        final StoredProgress stored = progress(MilestoneState.UNLOCKED,
                objective("logs", ObjectiveType.HAND_IN, 2048, 2048, true));

        final List<TrackValidation.Problem> problems = TrackValidation.validate(lowered("logs", 1000), stored);

        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.get(0).message().contains("already completed"), problems.toString());
    }

    @Test
    void anUnchangedFileAgainstACompletedObjectiveIsFine() {
        final StoredProgress stored = progress(MilestoneState.UNLOCKED,
                objective("logs", ObjectiveType.HAND_IN, 2048, 2048, true));

        assertTrue(TrackValidation.validate(track, stored).isEmpty());
    }

    // ---------------------------------------------------------------- order

    @Test
    void appendingAMilestoneIsAlwaysAllowed() {
        // The planned response to a track that finishes early, and the whole reason milestones are
        // not compiled in.
        final StoredProgress stored = progress(MilestoneState.UNLOCKED,
                objective("logs", ObjectiveType.HAND_IN, 2048, 2048, true));
        final MilestoneTrack longer = new MilestoneTrack(List.of(
                new Milestone("waiting", Unlock.BORDER, 20, 0, false, List.of()),
                new Milestone("foothold", Unlock.BORDER, 99, 30, false, List.of(LOGS, GATE)),
                new Milestone("beyond", Unlock.BORDER, 8000, 200, false, List.of(LOGS, GATE))));

        assertTrue(TrackValidation.validate(longer, stored).isEmpty());
    }

    @Test
    void movingAnUnlockedMilestoneBehindALockedOneIsRefused() {
        // The track is linear and its order is the file's, so what has been finished has to stay at
        // the front of it. Without this rule a file edit could leave the engine with no answer to
        // "what comes next".
        final StoredProgress stored = new StoredProgress(
                List.of(new StoredProgress.StoredMilestone("foothold", MilestoneState.UNLOCKED),
                        new StoredProgress.StoredMilestone("waiting", MilestoneState.LOCKED)),
                List.of());

        final List<TrackValidation.Problem> problems = TrackValidation.validate(track, stored);

        assertEquals(1, problems.size(), problems.toString());
        assertEquals("waiting", problems.get(0).milestoneKey());
    }

    @Test
    void aFreshSeasonValidatesAgainstAnythingAtAll() {
        assertTrue(TrackValidation.validate(track, StoredProgress.none()).isEmpty());
    }

    @Test
    void everyProblemIsReportedRatherThanOnlyTheFirst() {
        // A reload command that names one mistake at a time turns a five-minute edit into five
        // reloads.
        final StoredProgress stored = new StoredProgress(
                List.of(new StoredProgress.StoredMilestone("gone", MilestoneState.UNLOCKED),
                        new StoredProgress.StoredMilestone("also-gone", MilestoneState.ACTIVE)),
                List.of(objective("logs", ObjectiveType.STATISTIC, 10, 2048, false)));

        assertEquals(3, TrackValidation.validate(track, stored).size());
    }

    // ---------------------------------------------------------------- helpers

    private MilestoneTrack lowered(final String objectiveKey, final long target) {
        final List<Objective> objectives = track.milestone("foothold").orElseThrow().objectives().stream()
                .map(objective -> objective.key().equals(objectiveKey)
                        ? new Objective(objective.key(), objective.type(), objective.role(), target,
                        objective.items(), objective.statistic(), objective.subjects(),
                        objective.advancement())
                        : objective)
                .toList();
        return new MilestoneTrack(List.of(
                new Milestone("waiting", Unlock.BORDER, 20, 0, false, List.of()),
                new Milestone("foothold", Unlock.BORDER, 99, 30, false, objectives)));
    }

    private static StoredProgress progress(final MilestoneState state,
                                           final StoredProgress.StoredObjective objective) {
        return new StoredProgress(
                List.of(new StoredProgress.StoredMilestone("waiting", MilestoneState.UNLOCKED),
                        new StoredProgress.StoredMilestone("foothold", state)),
                List.of(objective));
    }

    private static StoredProgress.StoredObjective objective(final String key, final ObjectiveType type,
                                                            final long amount, final long target,
                                                            final boolean completed) {
        return new StoredProgress.StoredObjective("foothold", key, type, amount, target, completed);
    }
}
