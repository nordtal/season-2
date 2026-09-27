package eu.nordtal.s2.proxy.gate;

import eu.nordtal.s2.common.SeasonPhase;
import eu.nordtal.s2.common.access.AccessState;
import eu.nordtal.s2.common.access.MemberState;

/**
 * What the login gate decided, and therefore which screen the player gets.
 *
 * An unlinked account is refused as unlinked in every phase, since a link code helps it most.
 */
public enum GateOutcome {

    /** Linked, a member, and whatever the current phase asks for on top. */
    ALLOW,

    /** No Discord account is linked to this UUID: issue a link code and show it. */
    NOT_LINKED,

    /** Linked, but that Discord account has left the guild or is banned. */
    NOT_MEMBER,

    /** {@link SeasonPhase#SMP} and no access period is running. */
    NO_ACCESS,

    /** {@link SeasonPhase#PRE_LAUNCH}, linked member, no access period bought yet. */
    PRE_LAUNCH_BUY,

    /** {@link SeasonPhase#PRE_LAUNCH}, linked member, and a period already bought. */
    PRE_LAUNCH_READY;

    /** Walks the phase table once. */
    public static GateOutcome of(final AccessState state) {
        if (!state.linked()) {
            return NOT_LINKED;
        }
        if (state.memberState() != MemberState.MEMBER) {
            return NOT_MEMBER;
        }
        return switch (state.phase()) {
            // Free for every linked member: during maintenance they are let in and then held in limbo.
            case PRE_EVENT, START_EVENT, MAINTENANCE -> ALLOW;
            // The admin flag is a free pass, else the admin who switches the phase to SMP is disconnected by it.
            case SMP -> state.accessActive() || state.admin() ? ALLOW : NO_ACCESS;
            // Before opening, only an admin belongs here; accessBought(), not accessActive(), since it waits.
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
