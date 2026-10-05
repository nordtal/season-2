package eu.nordtal.season.smp.milestone;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Whether a reloaded milestone file may replace the running one.
 *
 * It refuses whatever would orphan stored progress, and must allow lowering a live target, the finest escape hatch.
 */
public final class TrackValidation {

    private TrackValidation() {}

    /** One reason a reload was refused, worded to go straight into a command's reply. */
    public record Problem(
            @Nullable String milestoneKey, @Nullable String objectiveKey, String message) {

        public Problem {
            Objects.requireNonNull(message, "message");
        }

        @Override
        public String toString() {
            if (milestoneKey == null) {
                // Nothing in the file to point at: the track as a whole is the problem.
                return message;
            }
            if (objectiveKey == null) {
                return "milestone '" + milestoneKey + "': " + message;
            }
            return "objective '" + milestoneKey + "/" + objectiveKey + "': " + message;
        }
    }

    /**
     * Checks a track against what the database holds, reporting every problem rather than the first.
     *
     * @param track    the track just parsed out of the file
     * @param progress the rows currently in {@code smp_milestone} and {@code smp_objective}
     * @return every problem, in the order found; empty means the file may replace the running one
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
                // Already reported above as a missing milestone.
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
            // A target change on a LIVE objective is deliberately not a problem.
        }

        problems.addAll(orderProblems(track, progress));
        return List.copyOf(problems);
    }

    /** The unlocked milestones have to stay a prefix of the file's order. */
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
