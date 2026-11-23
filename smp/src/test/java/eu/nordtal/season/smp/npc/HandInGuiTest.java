package eu.nordtal.season.smp.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class HandInGuiTest {

    @Test
    void theItemsNotCountedGoBackFromTheLastStackOn() {
        assertEquals(List.of(0, 22, 32), HandInGui.unpaid(List.of(64, 64, 32), 54));
    }

    @Test
    void aWholeCreditPutsNothingBack() {
        assertEquals(List.of(0, 0), HandInGui.unpaid(List.of(64, 32), 0));
    }

    @Test
    void nothingCountedPutsEverythingBack() {
        assertEquals(List.of(64, 32), HandInGui.unpaid(List.of(64, 32), 96));
    }

    @Test
    void threeRowChestHasExplicitFiveDepositSlotsInRowZeroMiddle() {
        assertEquals(3, HandInPanel.ROWS);
        assertEquals(List.of(2, 3, 4, 5, 6), HandInPanel.DEPOSIT_SLOTS);
        for (int slot = 0; slot < HandInPanel.ROWS * 9; slot++) {
            assertEquals(HandInPanel.DEPOSIT_SLOTS.contains(slot), HandInPanel.isDeposit(slot), "slot " + slot);
        }
        assertFalse(HandInPanel.isDeposit(0));
        assertFalse(HandInPanel.isDeposit(1));
        assertFalse(HandInPanel.isDeposit(7));
        assertFalse(HandInPanel.isDeposit(8));
        assertTrue(HandInPanel.isDeposit(2));
        assertTrue(HandInPanel.isDeposit(6));
        assertEquals(List.of(20, 21, 22, 23, 24), HandInPanel.CONFIRM_SLOTS);
        assertNotNull(HandInPanel.title("Iron", "120/256", false, "Hand in", false));
        assertNotNull(HandInPanel.title("Iron", "Doesn't count", true, "Hand in", false));
        assertNotNull(HandInPanel.title("Iron", "Finished", false, "Hand in", true));
    }

    @Test
    void offlineReturnAmountsReturnAllOnFailedCreditAndUnpaidTailOnPartialCredit() {
        assertEquals(List.of(64, 32), ObjectivesMenu.offlineReturnAmounts(List.of(64, 32), 96, -1));
        assertEquals(List.of(0, 22, 32), ObjectivesMenu.offlineReturnAmounts(List.of(64, 64, 32), 160, 106));
        assertEquals(List.of(0, 0), ObjectivesMenu.offlineReturnAmounts(List.of(64, 32), 96, 96));
    }
}
