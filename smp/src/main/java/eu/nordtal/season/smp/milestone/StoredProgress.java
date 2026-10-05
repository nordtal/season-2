package eu.nordtal.season.smp.milestone;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** What the database holds about the track, as plain values, so {@link TrackValidation} is testable without one. */
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
     * @param target       what was asked for, copied from the file, so a lowered target updates this column
     * @param completed    whether it has completed and paid out
     */
    public record StoredObjective(
            String milestoneKey, String key, ObjectiveType type, long amount, long target, boolean completed) {

        public StoredObjective {
            Objects.requireNonNull(milestoneKey, "milestoneKey");
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(type, "type");
        }
    }

    private final List<StoredMilestone> milestones;
    private final List<StoredObjective> objectives;

    public StoredProgress(final List<StoredMilestone> milestones, final List<StoredObjective> objectives) {
        this.milestones = List.copyOf(Objects.requireNonNull(milestones, "milestones"));
        this.objectives = List.copyOf(Objects.requireNonNull(objectives, "objectives"));
    }

    /** Returns an empty progress, which is what a fresh season looks like. */
    public static StoredProgress none() {
        return new StoredProgress(List.of(), List.of());
    }

    /** Returns every {@code smp_milestone} row. */
    public List<StoredMilestone> milestones() {
        return milestones;
    }

    /** Returns every {@code smp_objective} row. */
    public List<StoredObjective> objectives() {
        return objectives;
    }

    /** Returns whether nothing has been written yet. */
    public boolean isEmpty() {
        return milestones.isEmpty() && objectives.isEmpty();
    }

    /** Returns the stored objective row, if there is one. */
    public Optional<StoredObjective> objective(final String milestoneKey, final String objectiveKey) {
        return objectives.stream()
                .filter(objective -> objective.milestoneKey().equals(milestoneKey)
                        && objective.key().equals(objectiveKey))
                .findFirst();
    }

    /** Returns the stored milestone row, if there is one. */
    public Optional<StoredMilestone> milestone(final String key) {
        return milestones.stream()
                .filter(milestone -> milestone.key().equals(key))
                .findFirst();
    }
}
