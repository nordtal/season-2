package eu.nordtal.s2.smp.milestone;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.bukkit.NamespacedKey;

/**
 * One objective of one milestone, as the milestone file defines it; {@code smp_objective} holds its progress.
 *
 * @param key unique within its milestone and what stored progress is matched by, so renaming one is refused
 * @param type how progress is measured
 * @param role what it is for; never read by the engine, only by a person reviewing a diff
 * @param target what has to be reached; for {@code ADVANCEMENT}, a count of distinct players
 * @param items for {@code HAND_IN}: the item names any of which count
 * @param statistic for {@code STATISTIC}: the Bukkit statistic name, e.g. {@code MINE_BLOCK}
 * @param subjects for {@code STATISTIC}: the materials or entity types the statistic is summed over
 * @param advancement for {@code ADVANCEMENT}: the advancement key, e.g. {@code minecraft:story/mine_diamond}
 */
public record Objective(
        String key,
        ObjectiveType type,
        String role,
        long target,
        List<String> items,
        String statistic,
        List<String> subjects,
        String advancement) {

    public Objective {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(type, "type");
        items = items == null ? List.of() : List.copyOf(items);
        subjects = subjects == null ? List.of() : List.copyOf(subjects);
        role = role == null ? "" : role;
        statistic = statistic == null ? "" : statistic;
        advancement = advancement == null ? "" : advancement;
    }

    /** Returns the advancement this objective names, empty when the name is blank or not a key. */
    public Optional<NamespacedKey> advancementKey() {
        return Optional.ofNullable(NamespacedKey.fromString(advancement));
    }

    /** Returns whether this objective counts distinct players: the milestone's one participation gate. */
    public boolean isParticipationGate() {
        return type == ObjectiveType.ADVANCEMENT;
    }
}
