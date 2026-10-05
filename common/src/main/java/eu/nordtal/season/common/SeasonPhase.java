package eu.nordtal.season.common;

import org.jspecify.annotations.Nullable;

/**
 * The phases of season 2, which decide who may join and where they land.
 *
 * The current value is the one row of {@code season_phase}; adding a phase is a migration.
 */
public enum SeasonPhase {

    /**
     * Before the network first opens; only admins get in, everybody else sees a countdown.
     *
     * The initial state, left only by an admin.
     */
    PRE_LAUNCH,

    /** Before the start event; any linked, non-banned member lands in the {@code hunger-games} lobby. */
    PRE_EVENT,

    /** The hunger games start event; admission as in {@link #PRE_EVENT}, landing on {@code hunger-games}. */
    START_EVENT,

    /** The season proper; players land on {@code smp} and also need an active access period. */
    SMP,

    /**
     * Planned work; members wait in {@code limbo} while admins reach the servers.
     *
     * Also the phase of a process that has never read the row.
     */
    MAINTENANCE;

    /**
     * Parses a value read from {@code season_phase.phase}.
     *
     * @param value the stored string, may be {@code null}
     * @return the matching phase, or {@link #MAINTENANCE} for {@code null} or anything unrecognised, so an unreadable
     *     phase is never more permissive than the real one
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
