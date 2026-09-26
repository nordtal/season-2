package eu.nordtal.s2.common;

import org.jspecify.annotations.Nullable;

/**
 * The phases season 2 moves through, deciding who may join and where they land.
 *
 * The current value is the one row of {@code season_phase}; the names are the stored strings, restricted
 * by a {@code CHECK}, so adding one is a migration.
 */
public enum SeasonPhase {

    /**
     * Before the network has ever opened; only admins get in, everybody else sees a countdown to launch.
     *
     * The initial state. Nothing leaves it on its own: an admin decides when the network opens.
     */
    PRE_LAUNCH,

    /** Before the start event; any linked, non-banned member lands in the {@code hunger-games} lobby. */
    PRE_EVENT,

    /** The hunger games start event; admission as in {@link #PRE_EVENT}, landing on {@code hunger-games}. */
    START_EVENT,

    /** The season proper; players land on {@code smp} and also need an active access period. */
    SMP,

    /**
     * Planned work; members are held in {@code limbo} while admins reach the servers.
     *
     * Also the fallback when a process has never read the row, as it puts players somewhere harmless.
     */
    MAINTENANCE;

    /**
     * Parses a value read from {@code season_phase.phase}.
     *
     * @param value the stored string, may be {@code null}
     * @return the matching phase, or {@link #MAINTENANCE} for {@code null} or anything
     *         unrecognised - an unreadable phase must never be more permissive than the real one
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
