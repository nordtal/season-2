package eu.nordtal.season.smp.milestone;

import java.util.Optional;

/** The ways an objective's progress is measured, the values {@code smp_objective.type} allows. */
public enum ObjectiveType {

    /** Counts items delivered at the spawn NPC. The share of a player is the amount that player delivered. */
    HAND_IN,

    /**
     * Sums a vanilla statistic over all players.
     * The share of a player is that player's increase since the objective started.
     */
    STATISTIC,

    /** Counts the distinct players who earned an advancement. The share of a player is 1 or 0. */
    ADVANCEMENT;

    /** Parses a config or {@code smp_objective.type} value, ignoring case, and returns empty for any other value. */
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
