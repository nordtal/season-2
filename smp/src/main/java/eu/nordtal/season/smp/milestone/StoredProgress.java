package eu.nordtal.season.smp.milestone;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** The rows of {@code smp_milestone} and {@code smp_objective} as plain values for {@link TrackValidation}. */
public final class StoredProgress {

    /** A row of {@code smp_milestone}. */
    public record StoredMilestone(String key, MilestoneState state) {

        public StoredMilestone {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(state, "state");
        }
    }

    /** A row of {@code smp_objective}. */
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

    public static StoredProgress none() {
        return new StoredProgress(List.of(), List.of());
    }

    public List<StoredMilestone> milestones() {
        return milestones;
    }

    public List<StoredObjective> objectives() {
        return objectives;
    }

    public boolean isEmpty() {
        return milestones.isEmpty() && objectives.isEmpty();
    }

    public Optional<StoredObjective> objective(final String milestoneKey, final String objectiveKey) {
        return objectives.stream()
                .filter(objective -> objective.milestoneKey().equals(milestoneKey)
                        && objective.key().equals(objectiveKey))
                .findFirst();
    }

    public Optional<StoredMilestone> milestone(final String key) {
        return milestones.stream()
                .filter(milestone -> milestone.key().equals(key))
                .findFirst();
    }
}
