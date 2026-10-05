package eu.nordtal.season.smp.milestone;

import java.util.Optional;

/** The three ways an objective's progress is measured, CHECK-constrained in {@code smp_objective.type}. */
public enum ObjectiveType {

    /** Items delivered at the spawn NPC; an individual's share is the amount they delivered. */
    HAND_IN,

    /**
     * A vanilla statistic summed across all players, each one's share their own increase since the objective started.
     *
     * Active statistics only, by content rule: a passive one would pay every player for being online.
     */
    STATISTIC,

    /**
     * How many distinct players earned a given advancement; an individual's share is 1 or 0.
     *
     * Every milestone has one as its participation gate, which three industrious people cannot finish alone.
     */
    ADVANCEMENT;

    /** Parses a config or {@code smp_objective.type} value, empty for anything else and never an exception. */
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
