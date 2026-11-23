package eu.nordtal.season.smp.wheel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ItemHandoutTest {

    @Test
    void anInventoryADuelStillRestoresWaitsForTheRestore() {
        assertEquals(ItemHandout.Delivery.WAIT, ItemHandout.Delivery.of(true, false, true));
    }

    @Test
    void aDeadPlayerWaitsForTheRespawn() {
        assertEquals(ItemHandout.Delivery.WAIT, ItemHandout.Delivery.of(true, true, false));
    }

    @Test
    void anOfflinePlayerGetsTheItemsAtTheNextJoin() {
        assertEquals(ItemHandout.Delivery.STORE, ItemHandout.Delivery.of(false, false, true));
    }

    @Test
    void aFreePlayerGetsTheItemsNow() {
        assertEquals(ItemHandout.Delivery.GIVE, ItemHandout.Delivery.of(true, false, false));
    }
}
