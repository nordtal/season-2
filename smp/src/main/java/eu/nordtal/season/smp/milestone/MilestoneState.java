package eu.nordtal.season.smp.milestone;

import java.util.Optional;

/**
 * The values {@code smp_milestone.state} allows.
 *
 * The engine keeps at most one milestone {@link #ACTIVE}, the schema does not.
 */
public enum MilestoneState {

    /** An earlier milestone in the file is not unlocked. */
    LOCKED,

    /** The objectives of the milestone count progress. */
    ACTIVE,

    /** The milestone has unlocked. */
    UNLOCKED;

    /** Parses a {@code smp_milestone.state} value and returns {@link #LOCKED} for any other value. */
    public static MilestoneState fromDatabase(final String name) {
        return parse(name).orElse(LOCKED);
    }

    private static Optional<MilestoneState> parse(final String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        for (final MilestoneState state : values()) {
            if (state.name().equalsIgnoreCase(name.trim())) {
                return Optional.of(state);
            }
        }
        return Optional.empty();
    }
}
