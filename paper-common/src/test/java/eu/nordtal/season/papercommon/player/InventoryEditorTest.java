package eu.nordtal.season.papercommon.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.database.mode.ModeStateStore.Container;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Which slot of a player's container each slot of the editor's window shows. */
class InventoryEditorTest {

    @Test
    void theInventoryShowsTheMainSlotsTheHotbarTheArmourFromTheHelmetAndTheOffhand() {
        assertEquals(9, InventoryEditor.slotOf(Container.INVENTORY, 0));
        assertEquals(35, InventoryEditor.slotOf(Container.INVENTORY, 26));
        assertEquals(0, InventoryEditor.slotOf(Container.INVENTORY, 27));
        assertEquals(8, InventoryEditor.slotOf(Container.INVENTORY, 35));
        assertEquals(39, InventoryEditor.slotOf(Container.INVENTORY, 36), "the helmet");
        assertEquals(36, InventoryEditor.slotOf(Container.INVENTORY, 39), "the boots");
        assertEquals(40, InventoryEditor.slotOf(Container.INVENTORY, 40), "the offhand");
        assertEquals(-1, InventoryEditor.slotOf(Container.INVENTORY, 41));
        assertEquals(-1, InventoryEditor.slotOf(Container.INVENTORY, 44));
    }

    @Test
    void everySlotOfTheInventoryIsShownExactlyOnce() {
        final Set<Integer> shown = new HashSet<>();
        for (int view = 0; view < InventoryEditor.rows(Container.INVENTORY) * 9; view++) {
            final int slot = InventoryEditor.slotOf(Container.INVENTORY, view);
            if (slot >= 0) {
                assertTrue(shown.add(slot), "slot " + slot + " twice");
            }
        }
        assertEquals(41, shown.size());
    }

    @Test
    void theEnderChestShowsItsSlotsInOrder() {
        assertEquals(0, InventoryEditor.slotOf(Container.ENDER_CHEST, 0));
        assertEquals(26, InventoryEditor.slotOf(Container.ENDER_CHEST, 26));
        assertEquals(-1, InventoryEditor.slotOf(Container.ENDER_CHEST, 27));
        assertEquals(3, InventoryEditor.rows(Container.ENDER_CHEST));
    }
}
