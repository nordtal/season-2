package eu.nordtal.season.smp.duel;

/** What a move onto or off the negotiation area does to the player who made it. */
enum AreaStep {
    /** Nothing changes for the player. */
    NONE,
    /** A negotiating player stepped off and leaves the negotiation. */
    LEAVE,
    /** The area is held: the step is undone and the player pushed back. */
    REFUSE,
    /** The player stands alone on the area and waits for a second one. */
    WAIT,
    /** The player is the second one, and both windows open. */
    JOIN;

    /**
     * @param wasOn      the player stood on the area before the move
     * @param on         the player stands on the area after the move
     * @param negotiates the player is one of the two on the area
     * @param left       the player closed the window and has not stepped off since
     * @param fights     the player fights the running duel
     * @param held       two players negotiate or a duel runs
     * @param waiting    a first player waits on the area
     */
    static AreaStep of(
            final boolean wasOn,
            final boolean on,
            final boolean negotiates,
            final boolean left,
            final boolean fights,
            final boolean held,
            final boolean waiting) {
        if (!on) {
            return negotiates ? LEAVE : NONE;
        }
        if (negotiates || left || fights) {
            return NONE;
        }
        if (held) {
            // A player already standing on the area when it became held is not moved.
            return wasOn ? NONE : REFUSE;
        }
        return waiting ? JOIN : WAIT;
    }
}
