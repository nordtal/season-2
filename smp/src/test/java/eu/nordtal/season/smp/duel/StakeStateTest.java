package eu.nordtal.season.smp.duel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class StakeStateTest {

    @Test
    void anOfferStaysBetweenZeroAndTheBalance() {
        final StakeState state = new StakeState();
        state.balanceIs(0, 40);

        assertFalse(state.step(0, -1));
        assertTrue(state.step(0, StakeState.SHIFT_STEP));
        assertTrue(state.step(0, 100));
        assertEquals(40, state.offer(0));
        assertFalse(state.step(0, 1));
    }

    @Test
    void aNegativeBalanceOffersNothing() {
        final StakeState state = new StakeState();
        state.balanceIs(1, -5);

        assertEquals(0, state.cap(1));
        assertFalse(state.step(1, 1));
    }

    @Test
    void everyChangeClearsBothConfirmations() {
        final StakeState state = new StakeState();
        state.balanceIs(0, 10);
        state.confirm(0, true);
        state.confirm(1, true);
        assertTrue(state.bothReady());

        state.step(0, 1);

        assertFalse(state.ready(0));
        assertFalse(state.ready(1));
    }

    @Test
    void aConfirmationNeedsAnEmptyCursor() {
        final StakeState state = new StakeState();

        assertEquals(StakeState.Refusal.CURSOR, state.confirm(0, false));
        assertFalse(state.ready(0));
        assertEquals(StakeState.Refusal.NONE, state.confirm(0, true));
        assertTrue(state.ready(0));
    }

    @Test
    void aBalanceBelowTheOfferCutsItAndClearsBothConfirmations() {
        final StakeState state = new StakeState();
        state.balanceIs(0, 40);
        state.step(0, 40);
        state.confirm(0, true);
        state.confirm(1, true);

        assertFalse(state.balanceIs(0, 45));
        assertTrue(state.bothReady());
        assertTrue(state.balanceIs(0, 30));

        assertEquals(30, state.offer(0));
        assertFalse(state.ready(0));
        assertFalse(state.ready(1));
    }

    @Test
    void aDepositDuringTheCountdownStopsItBeforeTheLock() {
        final StakeState state = new StakeState();
        state.confirm(0, true);
        state.confirm(1, true);
        assertTrue(state.startCountdown(3));
        assertEquals(StakeState.Tick.COUNTING, state.tick());
        assertEquals(StakeState.Tick.COUNTING, state.tick());

        state.changed();

        assertFalse(state.counting());
        assertEquals(StakeState.Tick.STOPPED, state.tick());
    }

    @Test
    void theCountdownLocksAfterItsLastSecond() {
        final StakeState state = new StakeState();
        state.confirm(0, true);
        state.confirm(1, true);
        state.startCountdown(2);

        assertEquals(StakeState.Tick.COUNTING, state.tick());
        assertEquals(StakeState.Tick.LOCK, state.tick());
        assertFalse(state.counting());
    }

    @Test
    void aWithdrawnConfirmationStopsTheCountdown() {
        final StakeState state = new StakeState();
        state.confirm(0, true);
        state.confirm(1, true);
        state.startCountdown(3);

        state.withdraw(1);

        assertEquals(StakeState.Tick.STOPPED, state.tick());
    }

    @Test
    void oneConfirmationStartsNoCountdown() {
        final StakeState state = new StakeState();
        state.confirm(0, true);

        assertFalse(state.startCountdown(3));
        assertFalse(state.counting());
    }
}
