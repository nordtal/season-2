package eu.nordtal.s2.smp.milestone;

import java.util.List;
import java.util.Objects;

/**
 * One objective of one milestone, as the milestone file defines it.
 *
 * <p>A value, not a row: {@code smp_objective} holds the <em>progress</em> and this holds the
 * <em>definition</em>, and the split is what lets a milestone be appended or a target lowered
 * without a migration.
 *
 * <p>The type-specific fields - {@link #items()}, {@link #statistic()} / {@link #subjects()},
 * {@link #advancement()} - share one record because a jcore spec is an interface with a fixed set
 * of keys. {@link TrackShape#validate} decides which combinations are legal; read it before adding
 * a field here.
 *
 * <p>{@link #items()} and {@link #subjects()} are strings, not {@code Material} and
 * {@code EntityType}: resolving them needs an initialised registry, which does not exist at
 * config-load time or in a test. The plugin binds them once, at enable.
 *
 * @param key         unique within its milestone; also what {@code smp_objective.key} stores and
 *                    what the loader matches stored progress by, so renaming one orphans its
 *                    progress and is refused
 * @param type        how progress is measured
 * @param role        what this objective is <em>for</em> - gathering, mining, combat, production,
 *                    exploration, participation. Never read by the engine; it exists so a human
 *                    reading a diff notices a correction that produced four mining objectives
 * @param target      what has to be reached. For {@code ADVANCEMENT} it is a count of distinct
 *                    players, which is the milestone's participation gate
 * @param items       for {@code HAND_IN}: the item names any of which count
 * @param statistic   for {@code STATISTIC}: the Bukkit statistic name, e.g. {@code MINE_BLOCK}
 * @param subjects    for {@code STATISTIC}: the materials or entity types the statistic is counted
 *                    over, summed
 * @param advancement for {@code ADVANCEMENT}: the advancement key, e.g.
 *                    {@code minecraft:story/mine_diamond}
 */
public record Objective(String key, ObjectiveType type, String role, long target,
                        List<String> items, String statistic, List<String> subjects,
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

    /**
     * @return whether this objective counts distinct players rather than a total. Every milestone
     *         has exactly one, and it is the participation gate
     */
    public boolean isParticipationGate() {
        return type == ObjectiveType.ADVANCEMENT;
    }
}
