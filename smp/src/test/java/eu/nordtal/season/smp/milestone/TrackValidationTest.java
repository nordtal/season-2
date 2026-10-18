package eu.nordtal.season.smp.milestone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The rule that decides whether a reloaded milestone file replaces the running one.
 *
 * It refuses a change that orphans stored progress and permits lowering the target of a live objective.
 */
class TrackValidationTest {

    private static final Objective GATE = new Objective(
            "gate",
            ObjectiveType.ADVANCEMENT,
            "participation",
            10,
            List.of(),
            "",
            List.of(),
            "minecraft:story/iron_tools",
            0,
            0);
    private static final Objective LOGS = new Objective(
            "logs", ObjectiveType.HAND_IN, "gathering", 2048, List.of("OAK_LOG"), "", List.of(), "", 0, 0);

    private final MilestoneTrack track = new MilestoneTrack(List.of(
            new Milestone("waiting", Unlock.BORDER, 20, false, List.of()),
            new Milestone("foothold", Unlock.BORDER, 99, false, List.of(LOGS, GATE))));

    @Test
    void loweringTheTargetOfALiveObjectiveIsAllowed() {
        // Without this check only an admin command can lower the target, and a config change cannot.
        final StoredProgress stored =
                progress(MilestoneState.ACTIVE, objective("logs", ObjectiveType.HAND_IN, 1500, 2048, false));

        assertTrue(
                TrackValidation.validate(lowered("logs", 1000), stored).isEmpty(),
                "lowering a live target must not be refused");
    }

    @Test
    void raisingTheTargetOfALiveObjectiveIsAlsoAllowed() {
        // Refusing the same edit in the other direction would let a typo be corrected only downwards.
        final StoredProgress stored =
                progress(MilestoneState.ACTIVE, objective("logs", ObjectiveType.HAND_IN, 100, 2048, false));

        assertTrue(TrackValidation.validate(lowered("logs", 4096), stored).isEmpty());
    }

    @Test
    void loweringATargetBelowTheCollectedProgressCompletesTheObjective() {
        // Validation lets it through, and {@code ObjectiveProgress} notices that the objective is already done.
        assertTrue(ObjectiveProgress.completesOnReload(1500, 1000));
        assertFalse(ObjectiveProgress.completesOnReload(900, 1000));
    }

    @Test
    void aRenamedMilestoneIsRefused() {
        // A rename looks like a deletion, and stored progress and paid aura have nothing to point at.
        final StoredProgress stored =
                progress(MilestoneState.ACTIVE, objective("logs", ObjectiveType.HAND_IN, 100, 2048, false));
        final MilestoneTrack renamed = new MilestoneTrack(List.of(
                new Milestone("waiting", Unlock.BORDER, 20, false, List.of()),
                new Milestone("first-steps", Unlock.BORDER, 99, false, List.of(LOGS, GATE))));

        final List<TrackValidation.Problem> problems = TrackValidation.validate(renamed, stored);

        assertEquals(1, problems.size(), problems.toString());
        assertEquals("foothold", problems.get(0).milestoneKey());
    }

    @Test
    void aDeletedObjectiveWithProgressIsRefused() {
        final StoredProgress stored =
                progress(MilestoneState.ACTIVE, objective("logs", ObjectiveType.HAND_IN, 100, 2048, false));
        final MilestoneTrack without = new MilestoneTrack(List.of(
                new Milestone("waiting", Unlock.BORDER, 20, false, List.of()),
                new Milestone("foothold", Unlock.BORDER, 99, false, List.of(GATE))));

        final List<TrackValidation.Problem> problems = TrackValidation.validate(without, stored);

        assertEquals(1, problems.size(), problems.toString());
        assertEquals("logs", problems.get(0).objectiveKey());
    }

    @Test
    void changingAnObjectivesTypeIsRefusedOnceItHasProgress() {
        // {@code amount} has a different unit per type.
        final StoredProgress stored =
                progress(MilestoneState.ACTIVE, objective("logs", ObjectiveType.STATISTIC, 100, 2048, false));

        final List<TrackValidation.Problem> problems = TrackValidation.validate(track, stored);

        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.get(0).message().contains("changed type"), problems.toString());
    }

    @Test
    void changingTheTargetOfACompletedObjectiveIsRefused() {
        // The objective already paid out at its old target.
        final StoredProgress stored =
                progress(MilestoneState.UNLOCKED, objective("logs", ObjectiveType.HAND_IN, 2048, 2048, true));

        final List<TrackValidation.Problem> problems = TrackValidation.validate(lowered("logs", 1000), stored);

        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.get(0).message().contains("already completed"), problems.toString());
    }

    @Test
    void anUnchangedFileAgainstACompletedObjectiveIsFine() {
        final StoredProgress stored =
                progress(MilestoneState.UNLOCKED, objective("logs", ObjectiveType.HAND_IN, 2048, 2048, true));

        assertTrue(TrackValidation.validate(track, stored).isEmpty());
    }

    @Test
    void appendingAMilestoneIsAlwaysAllowed() {
        final StoredProgress stored =
                progress(MilestoneState.UNLOCKED, objective("logs", ObjectiveType.HAND_IN, 2048, 2048, true));
        final MilestoneTrack longer = new MilestoneTrack(List.of(
                new Milestone("waiting", Unlock.BORDER, 20, false, List.of()),
                new Milestone("foothold", Unlock.BORDER, 99, false, List.of(LOGS, GATE)),
                new Milestone("beyond", Unlock.BORDER, 8000, false, List.of(LOGS, GATE))));

        assertTrue(TrackValidation.validate(longer, stored).isEmpty());
    }

    @Test
    void movingAnUnlockedMilestoneBehindALockedOneIsRefused() {
        // The track is linear in file order, so finished milestones stay at the front.
        final StoredProgress stored = new StoredProgress(
                List.of(
                        new StoredProgress.StoredMilestone("foothold", MilestoneState.UNLOCKED),
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
        final StoredProgress stored = new StoredProgress(
                List.of(
                        new StoredProgress.StoredMilestone("gone", MilestoneState.UNLOCKED),
                        new StoredProgress.StoredMilestone("also-gone", MilestoneState.ACTIVE)),
                List.of(objective("logs", ObjectiveType.STATISTIC, 10, 2048, false)));

        assertEquals(3, TrackValidation.validate(track, stored).size());
    }

    private MilestoneTrack lowered(final String objectiveKey, final long target) {
        final List<Objective> objectives = track.milestone("foothold").orElseThrow().objectives().stream()
                .map(objective -> objective.key().equals(objectiveKey)
                        ? new Objective(
                                objective.key(),
                                objective.type(),
                                objective.role(),
                                target,
                                objective.items(),
                                objective.statistic(),
                                objective.subjects(),
                                objective.advancement(),
                                0,
                                0)
                        : objective)
                .toList();
        return new MilestoneTrack(List.of(
                new Milestone("waiting", Unlock.BORDER, 20, false, List.of()),
                new Milestone("foothold", Unlock.BORDER, 99, false, objectives)));
    }

    private static StoredProgress progress(final MilestoneState state, final StoredProgress.StoredObjective objective) {
        return new StoredProgress(
                List.of(
                        new StoredProgress.StoredMilestone("waiting", MilestoneState.UNLOCKED),
                        new StoredProgress.StoredMilestone("foothold", state)),
                List.of(objective));
    }

    private static StoredProgress.StoredObjective objective(
            final String key, final ObjectiveType type, final long amount, final long target, final boolean completed) {
        return new StoredProgress.StoredObjective("foothold", key, type, amount, target, completed);
    }
}
