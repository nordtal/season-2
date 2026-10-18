package eu.nordtal.season.common;

import org.jspecify.annotations.Nullable;

/**
 * The phases of season 2.
 * The phase decides who joins and where they land.
 * {@code season_phase} holds the current one.
 */
public enum SeasonPhase {

    /** Only admins join. Everybody else sees a countdown. Only an admin leaves this initial phase. */
    PRE_LAUNCH,

    /** Every linked member who is not banned lands in the {@code hunger-games} lobby. */
    PRE_EVENT,

    /** The Hunger Games start event. Admission and landing as in {@link #PRE_EVENT}, but on {@code hunger-games}. */
    START_EVENT,

    /** Players land on {@code smp} and need an active access period. */
    SMP,

    /**
     * Admins reach the servers and members wait in {@code limbo}.
     * A process that has not read the row is in this phase.
     */
    MAINTENANCE;

    /**
     * Returns the phase stored in {@code season_phase.phase}.
     * Returns {@link #MAINTENANCE} if {@code value} is null or unknown.
     */
    public static SeasonPhase fromDatabase(final @Nullable String value) {
        if (value == null) {
            return MAINTENANCE;
        }
        for (final SeasonPhase phase : values()) {
            if (phase.name().equalsIgnoreCase(value)) {
                return phase;
            }
        }
        return MAINTENANCE;
    }
}
