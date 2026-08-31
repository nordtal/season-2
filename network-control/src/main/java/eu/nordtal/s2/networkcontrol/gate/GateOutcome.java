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
 * code is more useful than being told anything else.
 *
 * <h2>There is no maintenance refusal any more, decided 2026-08-31</h2>
 * This enum used to carry a fifth constant, {@code MAINTENANCE_CLOSED}, and
 * {@link SeasonPhase#MAINTENANCE} used to answer it for everybody but an admin.
 * docs/season-phases.md left "disconnect <b>or</b> hold in limbo" open while its own phase table
 * already said non-admins land in {@code limbo}; the owner settled it on <b>holding them</b>.
 * Maintenance is therefore not a gate decision at all now - it is a <em>routing</em> decision, made
 * by {@code eu.nordtal.s2.networkcontrol.routing.PhaseRouting} after this class has already said
 * {@link #ALLOW}. The one place a maintenance screen still appears is that router's fallback for a
 * {@code limbo} server the proxy does not have.
 *
 * <p>{@link AccessState#admin()} consequently plays no part in this class. It decides where a
 * player goes during maintenance, not whether they get in.
 */
public enum GateOutcome {

    /** Linked, a member, and whatever the current phase asks for on top. Route on. */
    ALLOW,

    /** No Discord account is linked to this UUID. Issue a link code and show it. */
    NOT_LINKED,

    /** Linked, but that Discord account has left the guild or is banned. */
    NOT_MEMBER,

    /** {@link SeasonPhase#SMP} and no access period is running. The only phase that refuses. */
    NO_ACCESS;

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
            // Free for every linked member. For the two event phases this is the decision the whole
            // phase mechanism exists to serve - the start event costs nothing but a linked account.
            // For MAINTENANCE it is the 2026-08-31 reversal: they are let in and then held in limbo.
            case PRE_EVENT, START_EVENT, MAINTENANCE -> ALLOW;
            case SMP -> state.accessActive() ? ALLOW : NO_ACCESS;
        };
    }

    /** @return whether this outcome lets the player through */
    public boolean allowed() {
        return this == ALLOW;
    }
}
