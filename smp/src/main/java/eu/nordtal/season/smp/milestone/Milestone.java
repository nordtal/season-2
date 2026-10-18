package eu.nordtal.season.smp.milestone;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.bukkit.NamespacedKey;

/**
 * A milestone of the track as the milestone file defines it.
 *
 * @param key the YAML key and {@code smp_milestone.key}
 * @param unlock what the milestone does when it unlocks
 * @param borderDiameter the border diameter, read only with {@link Unlock#BORDER}
 * @param adminUnlocked whether an admin unlocks the milestone instead of its objectives
 * @param objectives the objectives in file order
 */
public record Milestone(
        String key, Unlock unlock, int borderDiameter, boolean adminUnlocked, List<Objective> objectives) {

    public Milestone {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(unlock, "unlock");
        objectives = objectives == null ? List.of() : List.copyOf(objectives);
    }

    public Optional<Objective> objective(final String key) {
        return objectives.stream()
                .filter(objective -> objective.key().equals(key))
                .findFirst();
    }

    public Optional<Objective> gateFor(final NamespacedKey advancement) {
        return objectives.stream()
                .filter(Objective::isParticipationGate)
                .filter(objective ->
                        objective.advancementKey().filter(advancement::equals).isPresent())
                .findFirst();
    }

    public boolean hasNoObjectives() {
        return objectives.isEmpty();
    }
}
