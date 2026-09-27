package eu.nordtal.s2.smp.milestone;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One milestone of the track, as the milestone file defines it; all its objectives must complete before it unlocks.
 *
 * @param key the YAML key and {@code smp_milestone.key}; renaming one is refused by {@link TrackValidation}
 * @param unlock what finishing it hands the community
 * @param borderDiameter the Nordtal border as a diameter, used only with {@link Unlock#BORDER}
 * @param objectivePot the aura pot of each objective: {@code round((budget ÷ objectives) × 5, to 10)}
 * @param adminUnlocked whether an admin opens it rather than its objectives
 * @param objectives every objective, in file order; empty for the two opening milestones
 */
public record Milestone(
        String key,
        Unlock unlock,
        int borderDiameter,
        int objectivePot,
        boolean adminUnlocked,
        List<Objective> objectives) {

    public Milestone {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(unlock, "unlock");
        objectives = objectives == null ? List.of() : List.copyOf(objectives);
    }

    /** Returns the objective with this key, if this milestone declares it. */
    public Optional<Objective> objective(final String key) {
        return objectives.stream()
                .filter(objective -> objective.key().equals(key))
                .findFirst();
    }

    /**
     * Returns whether this milestone has nothing to finish, so the engine unlocks it once active unless an admin must.
     */
    public boolean hasNoObjectives() {
        return objectives.isEmpty();
    }

    /** Returns the milestone's whole aura budget: the pot times the number of objectives. */
    public int totalPot() {
        return objectivePot * objectives.size();
    }
}
