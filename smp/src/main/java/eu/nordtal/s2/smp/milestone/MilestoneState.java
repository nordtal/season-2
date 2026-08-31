package eu.nordtal.s2.smp.milestone;

import java.util.Optional;

/**
 * Where one milestone stands, mirroring {@code smp_milestone.state}'s CHECK constraint.
 *
 * <p>Exactly one milestone is {@link #ACTIVE} at a time. That is a rule of the engine rather than
 * of the schema - {@code V6__smp.sql} says so in its own comment - because a partial unique index
 * would also have to survive the moment between unlocking one milestone and activating the next.
 */
public enum MilestoneState {

    /** Not yet reachable: an earlier milestone in the file is still open. */
    LOCKED,

    /** The one milestone whose objectives are being worked on. */
    ACTIVE,

    /** Finished and paid out. */
    UNLOCKED;

    /**
     * @param name a value from {@code smp_milestone.state}
     * @return the state, or {@link #LOCKED} for anything unreadable - the state that assumes the
     *         least is the safe one to guess, the same way {@code SeasonPhase} falls back to
     *         {@code MAINTENANCE}
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
