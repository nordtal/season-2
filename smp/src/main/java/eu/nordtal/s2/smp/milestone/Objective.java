package eu.nordtal.s2.smp.milestone;

import java.util.List;
import java.util.Objects;

/**
 * One objective of one milestone, as the milestone file defines it.
 *
 * <p>A value, not a row: {@code smp_objective} holds the <em>progress</em> and this holds the
 * <em>definition</em>, and the split is what lets a milestone be appended or a target lowered
 * without a migration (docs/smp.md#where-a-milestone-is-defined).
 *
 * <h2>One record for three types</h2>
 * The fields that only apply to one type - {@link #items()}, {@link #statistic()} /
 * {@link #subjects()}, {@link #advancement()} - sit on the same record rather than in three
 * subclasses, because that is the shape the config system can express: a jcore spec is an interface
 * with a fixed set of keys, and a polymorphic entry in a YAML list would need a discriminator and a
 * hand-written reader anyway. {@link TrackShape#validate} is what makes the combination legal, and
 * it is the thing to read before adding a field here.
 *
 * <h2>Nothing here touches Bukkit</h2>
 * {@link #items()} and {@link #subjects()} are strings, not {@code Material} and {@code EntityType}.
 * Resolving them needs an initialised registry, which exists on a running server and not in a test
 * or at config-load time - so the names are carried as written and bound once, at enable, by the
 * plugin. A typo therefore fails at startup with a name in the message rather than at config load
 * with a stack trace, and this whole package stays testable.
 *
 * @param key         unique within its milestone; also what {@code smp_objective.key} stores and
 *                    what the loader matches stored progress by, so renaming one orphans its
 *                    progress and is refused
 * @param type        how progress is measured
 * @param role        what this objective is <em>for</em> - gathering, mining, combat, production,
 *                    exploration, participation. Never read by the engine and deliberately kept:
 *                    docs/smp.md says the roles are "what stops a correction from accidentally
 *                    producing four mining objectives", which is a job for a human reading a diff
 * @param target      what has to be reached. For {@code ADVANCEMENT} it is a count of distinct
 *                    players, which is the milestone's participation gate
 * @param items       for {@code HAND_IN}: the item names any of which count. A list rather than one
 *                    name because the track asks for "logs, any kind" and "bulk building blocks"
 * @param statistic   for {@code STATISTIC}: the Bukkit statistic name, e.g. {@code MINE_BLOCK}
 * @param subjects    for {@code STATISTIC}: the materials or entity types the statistic is counted
 *                    over, summed. A list for the same reason as {@code items} - the track asks for
 *                    "hostile mobs" and "raider", which are groups and not one entity
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
