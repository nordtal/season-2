package eu.nordtal.season.smp.milestone;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Checks a reloaded milestone file against the stored progress.
 * It refuses a file that leaves stored progress without a milestone or objective.
 * It accepts a lower target for an objective that has not completed.
 */
public final class TrackValidation {

    private TrackValidation() {}

    /** A reason to refuse a reload, worded for the reply of a command. */
    public record Problem(
            @Nullable String milestoneKey, @Nullable String objectiveKey, String message) {

        public Problem {
            Objects.requireNonNull(message, "message");
        }

        @Override
        public String toString() {
            if (milestoneKey == null) {
                return message;
            }
            if (objectiveKey == null) {
                return "milestone '" + milestoneKey + "': " + message;
            }
            return "objective '" + milestoneKey + "/" + objectiveKey + "': " + message;
        }
    }

    /**
     * Compares {@code track} with {@code progress}.
     *
     * @return every problem in the order found, empty when the file may replace the running track
     */
    public static List<Problem> validate(final MilestoneTrack track, final StoredProgress progress) {
        Objects.requireNonNull(track, "track");
        Objects.requireNonNull(progress, "progress");

        final List<Problem> problems = new ArrayList<>();

        for (final StoredProgress.StoredMilestone stored : progress.milestones()) {
            if (track.milestone(stored.key()).isEmpty()) {
                problems.add(new Problem(
                        stored.key(),
                        null,
                        "has stored progress but is not declared in the file any more. Renaming a "
                                + "milestone key orphans everything recorded against it; add the key "
                                + "back, or delete its rows deliberately if the season really is "
                                + "meant to forget it."));
            }
        }

        for (final StoredProgress.StoredObjective stored : progress.objectives()) {
            final var milestone = track.milestone(stored.milestoneKey());
            if (milestone.isEmpty()) {
                continue;
            }

            final var declared = milestone.get().objective(stored.key());
            if (declared.isEmpty()) {
                problems.add(new Problem(
                        stored.milestoneKey(),
                        stored.key(),
                        "has stored progress but is not declared in the file any more. This is also "
                                + "what a renamed objective key looks like from here."));
                continue;
            }

            final Objective objective = declared.get();
            if (objective.type() != stored.type()) {
                problems.add(new Problem(
                        stored.milestoneKey(),
                        stored.key(),
                        "changed type from " + stored.type() + " to " + objective.type()
                                + ", but " + stored.amount() + " of progress is already recorded "
                                + "against it - and `amount` means a different thing for each type."));
            }
            if (stored.completed() && objective.target() != stored.target()) {
                problems.add(new Problem(
                        stored.milestoneKey(),
                        stored.key(),
                        "has already completed and paid out at a target of " + stored.target()
                                + "; changing it to " + objective.target() + " would rewrite the "
                                + "arithmetic behind aura that is already in the ledger."));
            }
        }

        problems.addAll(orderProblems(track, progress));
        return List.copyOf(problems);
    }

    /** Returns a problem for every milestone that is not unlocked and is before an unlocked one in the file. */
    private static List<Problem> orderProblems(final MilestoneTrack track, final StoredProgress progress) {
        final List<Problem> problems = new ArrayList<>();

        int lastUnlockedPosition = -1;
        for (final StoredProgress.StoredMilestone stored : progress.milestones()) {
            if (stored.state() == MilestoneState.UNLOCKED) {
                lastUnlockedPosition = Math.max(lastUnlockedPosition, track.positionOf(stored.key()));
            }
        }
        if (lastUnlockedPosition < 0) {
            return problems;
        }

        for (final StoredProgress.StoredMilestone stored : progress.milestones()) {
            if (stored.state() == MilestoneState.UNLOCKED) {
                continue;
            }
            final int position = track.positionOf(stored.key());
            if (position >= 0 && position < lastUnlockedPosition) {
                problems.add(new Problem(
                        stored.key(),
                        null,
                        "is " + stored.state() + " but the file now places it before a milestone that "
                                + "is already UNLOCKED. The track is linear and its order is this "
                                + "file's, so what has been finished has to stay at the front of it."));
            }
        }
        return problems;
    }
}
