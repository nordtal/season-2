package eu.nordtal.s2.smp.milestone;

import java.util.Optional;

/**
 * The three ways an objective's progress is measured.
 *
 * <p>A closed set: {@code V6__smp.sql} CHECK-constrains {@code smp_objective.type} to exactly these
 * three, so adding a fourth costs a migration.
 */
public enum ObjectiveType {

    /**
     * Items delivered at the spawn NPC. An individual's share is the amount they delivered.
     *
     * <p>Nothing is hopper-fed: automated delivery would turn contribution counting into a race
     * between farms.
     */
    HAND_IN,

    /**
     * A vanilla statistic summed across all players; an individual's share is their own increase
     * since the objective started.
     *
     * <p><b>Active statistics only</b> - blocks mined, mobs killed, items crafted, trades made.
     * Never distance walked, time played or damage taken: a passive statistic would hand every
     * player a share simply for being online. Nothing in the code enforces this; it is a content
     * rule.
     */
    STATISTIC,

    /**
     * How many <em>distinct</em> players earned a given advancement. An individual's share is 1 or 0.
     *
     * <p>Every milestone carries exactly one of these as its participation gate: it is the only
     * type three industrious people cannot finish alone.
     */
    ADVANCEMENT;

    /**
     * @param name a value from the config or from {@code smp_objective.type}
     * @return the type, or empty for anything else - never an exception, because this parses a
     *         hand-edited file
     */
    public static Optional<ObjectiveType> parse(final String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        for (final ObjectiveType type : values()) {
            if (type.name().equalsIgnoreCase(name.trim())) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
