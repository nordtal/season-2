package eu.nordtal.season.smp.duel;

import org.jspecify.annotations.Nullable;

/** What the negotiation window says to one of its two players, read from both picks. */
enum KitAgreement {
    /** Nobody else stands on the negotiation area. */
    ALONE,
    /** This player has not picked a kit. */
    PICK,
    /** This player picked, the opponent has not. */
    WAITING,
    DIFFERENT,
    /** Both picked the same kit, so the countdown runs. */
    AGREED;

    /** A pick is the index of a kit in the window; {@code opponent} is false while this player stands alone. */
    static KitAgreement of(final boolean opponent, final @Nullable Integer mine, final @Nullable Integer theirs) {
        if (!opponent) {
            return ALONE;
        }
        if (mine == null) {
            return PICK;
        }
        if (theirs == null) {
            return WAITING;
        }
        return mine.equals(theirs) ? AGREED : DIFFERENT;
    }
}
