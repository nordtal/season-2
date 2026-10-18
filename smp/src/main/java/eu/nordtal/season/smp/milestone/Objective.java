package eu.nordtal.season.smp.milestone;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.bukkit.NamespacedKey;

/**
 * An objective of a milestone as the milestone file defines it.
 *
 * @param key the key, unique within the milestone
 * @param type how progress is measured
 * @param role a note for a reviewer of the file
 * @param target the amount that completes the objective, a count of distinct players for {@code ADVANCEMENT}
 * @param items for {@code HAND_IN}: the item names that count
 * @param statistic for {@code STATISTIC}: the Bukkit statistic name, for example {@code MINE_BLOCK}
 * @param subjects for {@code STATISTIC}: the materials or entity types the statistic sums over
 * @param advancement for {@code ADVANCEMENT}: the advancement key, for example {@code minecraft:story/mine_diamond}
 * @param auraBudget the aura divided among the contributors when the objective completes
 * @param spinBudget the wheel spins divided among the contributors when the objective completes
 */
public record Objective(
        String key,
        ObjectiveType type,
        String role,
        long target,
        List<String> items,
        String statistic,
        List<String> subjects,
        String advancement,
        int auraBudget,
        int spinBudget) {

    public Objective {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(type, "type");
        items = items == null ? List.of() : List.copyOf(items);
        subjects = subjects == null ? List.of() : List.copyOf(subjects);
        role = role == null ? "" : role;
        statistic = statistic == null ? "" : statistic;
        advancement = advancement == null ? "" : advancement;
    }

    /** Returns the key in {@code advancement}, empty when {@code advancement} is not a valid key. */
    public Optional<NamespacedKey> advancementKey() {
        return Optional.ofNullable(NamespacedKey.fromString(advancement));
    }

    /** Returns whether the type is {@code ADVANCEMENT}, the participation gate of the milestone. */
    public boolean isParticipationGate() {
        return type == ObjectiveType.ADVANCEMENT;
    }
}
