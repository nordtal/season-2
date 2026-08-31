package eu.nordtal.s2.networkcontrol.gate;

import eu.nordtal.s2.common.SeasonPhase;
import eu.nordtal.s2.common.access.AccessState;
import eu.nordtal.s2.common.access.MemberState;

/**
 * What the login gate decided, and therefore which screen the player gets.
 *
 * <p>This is docs/season-phases.md's gate flowchart as a single, total function of one
 * {@link AccessState} - the record the one login round trip returns, phase included. It exists
 * separately from {@link LoginGate} for two reasons:
 *
 * <ul>
 *   <li>The decision is the part worth testing, and it can be tested exhaustively - four phases
 *       times the account states - without a Velocity {@code LoginEvent} or a running proxy.</li>
 *   <li>{@link AccessState#mayJoin()} collapses the same table to one boolean, which is right for
 *       the fallback cache and the expiry sweep and useless for choosing between four different
 *       disconnect screens. Both derive from the same table; only one of them can be a boolean.</li>
 * </ul>
 *
 * <p>The order below is the order the questions are asked in, and it matters: an unlinked account
 * is refused as unlinked in every phase including {@code MAINTENANCE}, because being handed a link
 * code is more useful than being told the network is closed.
 */
public enum GateOutcome {

    /** Linked, a member, and whatever the current phase asks for on top. Route on. */
    ALLOW,

    /** No Discord account is linked to this UUID. Issue a link code and show it. */
    NOT_LINKED,

    /** Linked, but that Discord account has left the guild or is banned. */
    NOT_MEMBER,

    /** {@link SeasonPhase#SMP} and no access period is running. */
    NO_ACCESS,

    /** {@link SeasonPhase#MAINTENANCE} and not an admin. */
    MAINTENANCE_CLOSED;

    /**
     * Walks docs/season-phases.md's phase table once.
     *
     * @param state the answer to the one login query
     * @return what happens to this login
     */
    public static GateOutcome of(final AccessState state) {
        if (!state.linked()) {
            return NOT_LINKED;
        }
        if (state.memberState() != MemberState.MEMBER) {
            return NOT_MEMBER;
        }
        return switch (state.phase()) {
            // Free for every linked member. This is the decision the whole phase mechanism exists
            // to serve: the start event costs nothing but a linked account.
            case PRE_EVENT, START_EVENT -> ALLOW;
            case SMP -> state.accessActive() ? ALLOW : NO_ACCESS;
            case MAINTENANCE -> state.admin() ? ALLOW : MAINTENANCE_CLOSED;
        };
    }

    /** @return whether this outcome lets the player through */
    public boolean allowed() {
        return this == ALLOW;
    }
}
