package eu.nordtal.season.smp.milestone;

import java.util.Optional;

/**
 * Where one milestone stands, mirroring {@code smp_milestone.state}'s CHECK constraint.
 *
 * Exactly one milestone is {@link #ACTIVE} at a time; the engine enforces that, not the schema.
 */
public enum MilestoneState {

    /** Not yet reachable: an earlier milestone in the file is still open. */
    LOCKED,

    /** The one milestone whose objectives are being worked on. */
    ACTIVE,

    /** Finished and paid out. */
    UNLOCKED;

    /**
     * Parses a {@code smp_milestone.state} value, falling back to {@link #LOCKED}, the state that assumes the least.
     */
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
