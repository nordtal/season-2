package eu.nordtal.s2.common;

/**
 * The phases season 2 moves through. The phase decides who may join and where they land, so a wrong
 * value either opens the SMP to everyone or locks everybody out.
 *
 * <p>The current value is one row in PostgreSQL ({@code season_phase}); every process reads it and
 * nobody caches it as truth. The constant names are the exact strings stored in
 * {@code season_phase.phase}, which a {@code CHECK} constraint restricts to these five, so adding
 * one is a migration.
 */
public enum SeasonPhase {

    /**
     * Before the network has ever opened. Nobody but an admin gets in; everybody else is refused
     * with a screen that counts down to {@code season_phase.launch}.
     *
     * <p>The season's initial state. Nothing switches out of it on its own - the countdown reaching
     * zero changes what the server browser says and nothing else, because who may join is an
     * admin's decision rather than a timestamp set weeks earlier.
     */
    PRE_LAUNCH,

    /**
     * Before the start event. Players land in the {@code hunger-games} lobby, and any linked,
     * non-banned Discord member gets in - an access period is not required.
     */
    PRE_EVENT,

    /**
     * The hunger games start event itself. Same admission rule as {@link #PRE_EVENT}, and players
     * land on {@code hunger-games}.
     */
    START_EVENT,

    /**
     * The season proper. Players land on {@code smp}, and this is the only phase in which a linked
     * member also needs an active access period to get in.
     */
    SMP,

    /**
     * Planned work. A linked, non-banned member is let onto the network as in every other phase and
     * then held in {@code limbo}, where the explanation is shown; an admin ({@code
     * discord_user.admin}) is not moved and reaches the servers being worked on.
     *
     * <p>Also the value a process falls back to when it has never managed to read the row: it is
     * the one phase that puts a player somewhere harmless.
     */
    MAINTENANCE;

    /**
     * Parses a value read from {@code season_phase.phase}.
     *
     * @param value the stored string, may be {@code null}
     * @return the matching phase, or {@link #MAINTENANCE} for {@code null} or anything
     *         unrecognised - an unreadable phase must never be more permissive than the real one
     */
    public static SeasonPhase fromDatabase(final String value) {
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
