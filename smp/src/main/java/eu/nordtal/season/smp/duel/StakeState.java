package eu.nordtal.season.smp.duel;

/**
 * The two sides of a stake before the lock: each side's aura offer, balance and confirmation.
 *
 * Side 0 is the player who stepped on the area first. Every change to an offer clears both confirmations.
 */
final class StakeState {

    /** Why a confirmation was refused. */
    enum Refusal {
        NONE,
        CURSOR
    }

    /** The step of a click on plus or minus; a click with Shift steps by {@link #SHIFT_STEP}. */
    static final int STEP = 1;

    static final int SHIFT_STEP = 10;

    private final int[] offer = new int[2];
    private final int[] balance = new int[2];
    private final boolean[] ready = new boolean[2];

    int offer(final int side) {
        return offer[side];
    }

    int balance(final int side) {
        return balance[side];
    }

    /** The most a side may offer: its balance, and 0 while the balance is negative. */
    int cap(final int side) {
        return Math.max(0, balance[side]);
    }

    boolean ready(final int side) {
        return ready[side];
    }

    boolean bothReady() {
        return ready[0] && ready[1];
    }

    /**
     * Moves a side's offer by {@code delta}, kept between 0 and its cap.
     *
     * @return whether the offer changed, which clears both confirmations
     */
    boolean step(final int side, final int delta) {
        final int next = Math.clamp((long) offer[side] + delta, 0, cap(side));
        if (next == offer[side]) {
            return false;
        }
        offer[side] = next;
        changed();
        return true;
    }

    /** A side's items changed: both confirmations clear. */
    void changed() {
        ready[0] = false;
        ready[1] = false;
    }

    /** Confirms a side, which needs an empty cursor. */
    Refusal confirm(final int side, final boolean cursorEmpty) {
        if (!cursorEmpty) {
            return Refusal.CURSOR;
        }
        ready[side] = true;
        return Refusal.NONE;
    }

    /** Takes back a side's confirmation. */
    void withdraw(final int side) {
        ready[side] = false;
    }

    /**
     * Records a side's current balance and cuts an offer the balance no longer covers.
     *
     * @return whether the offer was cut, which clears both confirmations
     */
    boolean balanceIs(final int side, final int current) {
        balance[side] = current;
        if (offer[side] <= cap(side)) {
            return false;
        }
        offer[side] = cap(side);
        changed();
        return true;
    }
}
