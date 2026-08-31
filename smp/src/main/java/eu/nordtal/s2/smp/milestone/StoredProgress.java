package eu.nordtal.s2.smp.milestone;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What the database currently holds about the track: the rows of {@code smp_milestone} and
 * {@code smp_objective}, as values with no JDBI on them.
 *
 * <p>They exist so {@link TrackValidation} can be a pure function of "the file" and "the rows".
 * The validation is the one piece of the milestone engine that is easy to get subtly wrong and
 * expensive to get wrong in production - it is what stands between a config edit and a finished
 * milestone quietly disappearing - so it is worth being able to assert it without a database.
 */
public final class StoredProgress {

    /**
     * One row of {@code smp_milestone}.
     *
     * @param key   the milestone key, which joins to the file
     * @param state where it stands
     */
    public record StoredMilestone(String key, MilestoneState state) {

        public StoredMilestone {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(state, "state");
        }
    }

    /**
     * One row of {@code smp_objective}.
     *
     * @param milestoneKey the milestone it belongs to
     * @param key          its own key within that milestone
     * @param type         how its progress was measured when it was created
     * @param amount       what has been collected so far
     * @param target       what was asked for. Copied out of the file when the objective was created,
     *                     which is why lowering the target in the file has to update this column
     *                     rather than only the file - that is the first escape hatch
     * @param completed    whether it has completed and paid out
     */
    public record StoredObjective(String milestoneKey, String key, ObjectiveType type,
                                  long amount, long target, boolean completed) {

        public StoredObjective {
            Objects.requireNonNull(milestoneKey, "milestoneKey");
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(type, "type");
        }
    }

    private final List<StoredMilestone> milestones;
    private final List<StoredObjective> objectives;

    public StoredProgress(final List<StoredMilestone> milestones,
                          final List<StoredObjective> objectives) {
        this.milestones = List.copyOf(Objects.requireNonNull(milestones, "milestones"));
        this.objectives = List.copyOf(Objects.requireNonNull(objectives, "objectives"));
    }

    /** @return an empty progress, which is what a fresh season looks like */
    public static StoredProgress none() {
        return new StoredProgress(List.of(), List.of());
    }

    /** @return every {@code smp_milestone} row */
    public List<StoredMilestone> milestones() {
        return milestones;
    }

    /** @return every {@code smp_objective} row */
    public List<StoredObjective> objectives() {
        return objectives;
    }

    /** @return whether nothing has been written yet */
    public boolean isEmpty() {
        return milestones.isEmpty() && objectives.isEmpty();
    }

    /**
     * @param milestoneKey a milestone key
     * @param objectiveKey an objective key
     * @return the stored row, if there is one
     */
    public Optional<StoredObjective> objective(final String milestoneKey, final String objectiveKey) {
        return objectives.stream()
                .filter(objective -> objective.milestoneKey().equals(milestoneKey)
                        && objective.key().equals(objectiveKey))
                .findFirst();
    }

    /**
     * @param key a milestone key
     * @return the stored row, if there is one
     */
    public Optional<StoredMilestone> milestone(final String key) {
        return milestones.stream().filter(milestone -> milestone.key().equals(key)).findFirst();
    }
}
