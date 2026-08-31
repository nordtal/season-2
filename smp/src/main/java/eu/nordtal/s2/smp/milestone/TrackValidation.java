package eu.nordtal.s2.smp.milestone;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Whether a reloaded milestone file may replace the one the season has been running on.
 *
 * <p>This is the single most consequential rule in the module, and it has two halves that pull in
 * opposite directions (docs/smp.md#where-a-milestone-is-defined):
 *
 * <ol>
 *   <li>It must <b>refuse a change that would orphan stored progress</b> - a renamed milestone key
 *       or a deleted objective would silently discard a finished piece of the season, and the
 *       people who did the work would simply see it gone.</li>
 *   <li>It must <b>explicitly permit lowering the {@code target} of a live objective</b>, because
 *       that is the finest of the three escape hatches for an objective that turns out to be
 *       impossible - and a validation that blocks it means every rescue becomes an admin command,
 *       which pays proportionally rather than in full.</li>
 * </ol>
 *
 * <p>A validation that only implemented the first half would look correct and would quietly delete
 * the first escape hatch, which is exactly the failure docs/smp.md warns about by name. Both halves
 * are asserted in {@code TrackValidationTest}.
 *
 * <h2>What is allowed, in one list</h2>
 * <ul>
 *   <li><b>Appending milestones</b> - the planned response to a track that finishes early.</li>
 *   <li><b>Adding objectives to a milestone that has not been unlocked yet.</b></li>
 *   <li><b>Changing the target of an objective that has not completed</b>, in either direction.
 *       Lowering is the escape hatch; raising is allowed because it is the same edit and refusing
 *       it would mean a typo could only ever be corrected downwards.</li>
 *   <li><b>Changing anything about an objective the database has never heard of</b> - items,
 *       statistic, advancement, role, pot. Only the parts the database stores can be inconsistent
 *       with it.</li>
 * </ul>
 *
 * <h2>What is refused, and why each one</h2>
 * <ul>
 *   <li><b>A stored milestone the file no longer declares.</b> Its progress, and any aura already
 *       paid against it, would have nothing to point at.</li>
 *   <li><b>A stored objective the file no longer declares.</b> Same, one level down - and this is
 *       what catches a renamed objective key, which looks like a deletion plus an addition.</li>
 *   <li><b>A change of type on an objective with stored progress.</b> {@code amount} means a
 *       different thing per type - items delivered, a statistic's increase, a count of distinct
 *       players - so carrying it across would be reading a number in the wrong unit.</li>
 *   <li><b>Any change of target on a <em>completed</em> objective.</b> It has already paid out, and
 *       an admin completion's {@code pot × (reached ÷ target)} refers to what was asked for at the
 *       time. Moving the target afterwards would rewrite the arithmetic behind aura that is already
 *       in the ledger.</li>
 *   <li><b>Reordering an unlocked milestone behind a locked one.</b> The track is linear and its
 *       order is the file's, so the unlocked milestones must stay a prefix of it. Without this a
 *       file edit could put a finished milestone after the one being worked on and leave the engine
 *       with no answer to "what comes next".</li>
 * </ul>
 */
public final class TrackValidation {

    private TrackValidation() {
    }

    /** One reason a reload was refused, written so it can go straight into a command's reply. */
    public record Problem(String milestoneKey, String objectiveKey, String message) {

        public Problem {
            Objects.requireNonNull(message, "message");
        }

        @Override
        public String toString() {
            if (objectiveKey == null) {
                return "milestone '" + milestoneKey + "': " + message;
            }
            return "objective '" + milestoneKey + "/" + objectiveKey + "': " + message;
        }
    }

    /**
     * Checks a track against what the database holds.
     *
     * @param track    the track just parsed out of the file
     * @param progress the rows currently in {@code smp_milestone} and {@code smp_objective}
     * @return every problem found, in the order they were found. Empty means the file may replace
     *         the running one. Every problem is reported rather than only the first, because a
     *         reload command that names one mistake at a time turns a five-minute edit into five
     *         reloads
     */
    public static List<Problem> validate(final MilestoneTrack track, final StoredProgress progress) {
        Objects.requireNonNull(track, "track");
        Objects.requireNonNull(progress, "progress");

        final List<Problem> problems = new ArrayList<>();

        for (final StoredProgress.StoredMilestone stored : progress.milestones()) {
            if (track.milestone(stored.key()).isEmpty()) {
                problems.add(new Problem(stored.key(), null,
                        "has stored progress but is not declared in the file any more. Renaming a "
                                + "milestone key orphans everything recorded against it; add the key "
                                + "back, or delete its rows deliberately if the season really is "
                                + "meant to forget it."));
            }
        }

        for (final StoredProgress.StoredObjective stored : progress.objectives()) {
            final var milestone = track.milestone(stored.milestoneKey());
            if (milestone.isEmpty()) {
                // Already reported above as a missing milestone; saying it again per objective
                // would bury the one line that matters under one line per objective.
                continue;
            }

            final var declared = milestone.get().objective(stored.key());
            if (declared.isEmpty()) {
                problems.add(new Problem(stored.milestoneKey(), stored.key(),
                        "has stored progress but is not declared in the file any more. This is also "
                                + "what a renamed objective key looks like from here."));
                continue;
            }

            final Objective objective = declared.get();
            if (objective.type() != stored.type()) {
                problems.add(new Problem(stored.milestoneKey(), stored.key(),
                        "changed type from " + stored.type() + " to " + objective.type()
                                + ", but " + stored.amount() + " of progress is already recorded "
                                + "against it - and `amount` means a different thing for each type."));
            }
            if (stored.completed() && objective.target() != stored.target()) {
                problems.add(new Problem(stored.milestoneKey(), stored.key(),
                        "has already completed and paid out at a target of " + stored.target()
                                + "; changing it to " + objective.target() + " would rewrite the "
                                + "arithmetic behind aura that is already in the ledger."));
            }
            // A target change on a LIVE objective is deliberately not a problem, in either
            // direction. Lowering it is the first escape hatch (docs/smp.md#when-an-objective-
            // turns-out-to-be-impossible) and the whole reason this method takes the file rather
            // than refusing every difference.
        }

        problems.addAll(orderProblems(track, progress));
        return List.copyOf(problems);
    }

    /**
     * The unlocked milestones have to stay a prefix of the file's order.
     *
     * <p>Checked separately because it is the one rule that is about the file as a whole rather
     * than about one key: any individual milestone can be moved, as long as what has already been
     * finished still comes first.
     */
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
                problems.add(new Problem(stored.key(), null,
                        "is " + stored.state() + " but the file now places it before a milestone that "
                                + "is already UNLOCKED. The track is linear and its order is this "
                                + "file's, so what has been finished has to stay at the front of it."));
            }
        }
        return problems;
    }
}
