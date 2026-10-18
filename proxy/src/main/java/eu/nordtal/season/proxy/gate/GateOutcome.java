package eu.nordtal.season.proxy.gate;

import eu.nordtal.season.common.SeasonPhase;
import eu.nordtal.season.database.access.AccessState;
import eu.nordtal.season.database.access.MemberState;

/** The decision of the login gate. Each value that is not {@code ALLOW} has a screen. */
public enum GateOutcome {

    /** The account is linked, is a member and meets the requirements of the phase. */
    ALLOW,

    /** No Discord account is linked to the UUID. The gate shows a link code. */
    NOT_LINKED,

    /** The Discord account has left the guild or is banned. */
    NOT_MEMBER,

    /** The phase is {@link SeasonPhase#SMP} and no period is running. */
    NO_ACCESS,

    /** The phase is {@link SeasonPhase#PRE_LAUNCH} and the member has bought no period. */
    PRE_LAUNCH_BUY,

    /** The phase is {@link SeasonPhase#PRE_LAUNCH} and the member has bought a period. */
    PRE_LAUNCH_READY,

    /** The network has reached its player limit. An admin is never refused for it. */
    FULL,

    /** The database does not answer and the cache does not hold the player as allowed. */
    TROUBLE;

    /** Returns the outcome for the access state. It is never {@link #FULL} or {@link #TROUBLE}. */
    public static GateOutcome of(final AccessState state) {
        if (!state.linked()) {
            return NOT_LINKED;
        }
        if (state.memberState() != MemberState.MEMBER) {
            return NOT_MEMBER;
        }
        return switch (state.phase()) {
            case PRE_EVENT, START_EVENT, MAINTENANCE -> ALLOW;
            case SMP -> state.accessActive() || state.admin() ? ALLOW : NO_ACCESS;
            case PRE_LAUNCH -> {
                if (state.admin()) {
                    yield ALLOW;
                }
                yield state.accessBought() ? PRE_LAUNCH_READY : PRE_LAUNCH_BUY;
            }
        };
    }

    /** Returns {@link #FULL} when the state is allowed, the player is not an admin and the network is full. */
    public static GateOutcome of(final AccessState state, final int online, final int maximum) {
        final GateOutcome outcome = of(state);
        return outcome == ALLOW && !state.admin() && online >= maximum ? FULL : outcome;
    }

    /**
     * Returns the outcome while the database does not answer. No player is exempt from the player limit.
     *
     * @param remembered whether the cache holds the player as allowed
     */
    public static GateOutcome withoutDatabase(final boolean remembered, final int online, final int maximum) {
        if (!remembered) {
            return TROUBLE;
        }
        return online >= maximum ? FULL : ALLOW;
    }

    /** Returns whether the outcome is {@link #ALLOW}. */
    public boolean allowed() {
        return this == ALLOW;
    }
}
