package eu.nordtal.s2.common;

/**
 * The phases season 2 moves through. The phase decides <b>who may join</b> and <b>where they
 * land</b>, which makes it a security-relevant value rather than a cosmetic one: the wrong phase
 * either opens the SMP to everyone or locks everybody out. See {@code docs/season-phases.md}.
 * <p>
 * The current value is <b>one row in PostgreSQL</b> ({@code season_phase}); every process reads it
 * and nobody caches it as truth. {@code eu.nordtal.s2.common.phase.PhaseDirectory} is how it is
 * read and how it is switched.
 * </p>
 * <p>
 * The ordering here is the network's routing order: the season runs {@link #PRE_EVENT} to
 * {@link #START_EVENT} to {@link #SMP}, and {@link #MAINTENANCE} sits at the end because it is the
 * one phase that is not a stage of the season but an interruption of any of them.
 * </p>
 * <p>
 * The names are the exact strings stored in {@code season_phase.phase}, which a database
 * {@code CHECK} constraint restricts to these four.
 * </p>
 */
public enum SeasonPhase {

    /**
     * Before the start event. The network is open, the lobby stands and teams register; players
     * land in the {@code hunger-games} lobby. Any linked, non-banned Discord member gets in -
     * <b>access is not required</b>, and that is the decision the whole phase mechanism exists to
     * serve.
     */
    PRE_EVENT,

    /**
     * The hunger games start event itself, from the countdown to the winner. Same admission rule as
     * {@link #PRE_EVENT} - linked member, not banned, no access needed - and players land on
     * {@code hunger-games}.
     */
    START_EVENT,

    /**
     * The season proper. Players land on {@code smp}, and this is the <b>only</b> phase in which a
     * linked member also needs an active access period to get in. Selling access before the SMP
     * begins is still possible and simply banks days.
     */
    SMP,

    /**
     * Planned work. <b>Admins only</b> - recognised by {@code discord_user.admin} - and everyone
     * else waits in {@code limbo} or is refused. It is also the value a process falls back to when
     * it has never managed to read the row at all: the state that lets nobody in is the safe one to
     * guess.
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
