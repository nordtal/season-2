package eu.nordtal.s2.networkcontrol.gate;

import eu.nordtal.s2.common.SeasonPhase;
import eu.nordtal.s2.common.access.AccessState;
import eu.nordtal.s2.common.access.MemberState;

/**
 * What the login gate decided, and therefore which screen the player gets.
 *
 * <p>A total function of one {@link AccessState} - the record the single login round trip returns,
 * phase included - kept separate from {@link LoginGate} so the decision can be tested exhaustively
 * without a running proxy. {@link AccessState#mayJoin()} collapses the same table to one boolean,
 * which is enough for the fallback cache and the expiry sweep but cannot choose between four
 * disconnect screens.</p>
 *
 * <p>The order the questions are asked in matters: an unlinked account is refused as unlinked in
 * every phase, because being handed a link code is more useful than anything else it could be
 * told.</p>
 *
 * <p>Maintenance is not a gate decision: a non-admin is admitted and then held in {@code limbo} by
 * {@code eu.nordtal.s2.networkcontrol.routing.PhaseRouting}. {@link AccessState#admin()} therefore
 * plays no part here except in {@link SeasonPhase#PRE_LAUNCH}, where it is the whole admission
 * rule.</p>
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

    /**
     * {@link SeasonPhase#PRE_LAUNCH}, linked member, <b>no access period bought yet</b>. The
     * network has not opened, so nobody is getting in either way - the screen counts down to the
     * opening and points out that a period can already be bought now, so that the SMP is playable
     * the moment the event is over.
     */
    PRE_LAUNCH_BUY,

    /**
     * {@link SeasonPhase#PRE_LAUNCH}, linked member, and a period already bought. Nothing is left
     * to do but wait: the screen says so and counts down.
     */
    PRE_LAUNCH_READY;

    /**
     * Walks the phase table once.
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
            // Free for every linked member: the event costs nothing but a linked account, and
            // during maintenance they are let in and then held in limbo.
            case PRE_EVENT, START_EVENT, MAINTENANCE -> ALLOW;
            // The admin flag is a free pass: an admin is on the network to run it, not to play a
            // bought period, and without this the admin who switches the phase to SMP is
            // disconnected by their own switch. A banned admin is still banned - member state is
            // asked first, above.
            case SMP -> state.accessActive() || state.admin() ? ALLOW : NO_ACCESS;
            // Before the opening, an admin is the only person the network is for; everybody else
            // gets one of two waiting screens.
            //
            // accessBought(), NOT accessActive(): a period bought during PRE_LAUNCH sits and waits
            // rather than burning, so asking whether it is running right now would show the buy-it
            // screen to the very people who just did.
            case PRE_LAUNCH -> {
                if (state.admin()) {
                    yield ALLOW;
                }
                yield state.accessBought() ? PRE_LAUNCH_READY : PRE_LAUNCH_BUY;
            }
        };
    }

    /** @return whether this outcome lets the player through */
    public boolean allowed() {
        return this == ALLOW;
    }
}
