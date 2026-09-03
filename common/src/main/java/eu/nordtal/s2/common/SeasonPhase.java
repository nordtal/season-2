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
 * The ordering here is the network's routing order: the season runs {@link #PRE_LAUNCH} to
 * {@link #PRE_EVENT} to {@link #START_EVENT} to {@link #SMP}, and {@link #MAINTENANCE} sits at the
 * end because it is the one phase that is not a stage of the season but an interruption of any of
 * them.
 * </p>
 * <p>
 * The names are the exact strings stored in {@code season_phase.phase}, which a database
 * {@code CHECK} constraint restricts to these five. Adding one is a migration:
 * {@code V8__pre_launch.sql} is the worked example.
 * </p>
 */
public enum SeasonPhase {

    /**
     * Before the network has ever opened. <b>Nobody but an admin gets in</b>, and everybody else is
     * refused with a screen that counts down to {@code season_phase.launch} - which of the three
     * screens they see depends on how far they already are, so the wait is also the onboarding:
     * a link code for an unlinked account, an invitation to buy the first month for a linked one
     * without a period, and a "you're all set" for somebody who has both. See
     * {@code eu.nordtal.s2.common.access.GateOutcome} and {@code docs/season-phases.md}.
     * <p>
     * This is the season's <b>initial</b> state and the one phase that has a date attached to it.
     * Nothing switches out of it on its own: the countdown reaching zero changes what the server
     * browser says and nothing else, because who may join is an admin's decision and not a
     * timestamp somebody set weeks earlier.
     * </p>
     */
    PRE_LAUNCH,

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
     * Planned work. <b>Everyone else waits in {@code limbo}</b>: a linked, non-banned member is let
     * onto the network exactly as in every other phase and is then held in the waiting room, where
     * the explanation is shown. An admin - recognised by {@code discord_user.admin} - is not moved
     * and reaches the servers being worked on.
     * <p>
     * <b>Decided 2026-08-31.</b> {@code docs/season-phases.md} used to leave "disconnect <b>or</b>
     * hold in limbo" open while its own phase table already said {@code limbo}; the owner settled it
     * on holding them. Before that, maintenance refused every non-admin at the login gate.
     * </p><p>
     * It is also the value a process falls back to when it has never managed to read the row at
     * all - not because it lets nobody in any more, but because it is the one phase that puts a
     * player somewhere harmless while the proxy works out where they really belong.
     * </p>
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
