package eu.nordtal.season.papercommon.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.database.mode.ModeStateStore.Container;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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

    @Test
    void aClickMovesStacksAsInAChest() {
        assertEquals(new InventoryEditor.Move(0, 0, false), InventoryEditor.move(false, 0, 0, false, 64));
        assertEquals(
                new InventoryEditor.Move(64, 0, false), InventoryEditor.move(false, 0, 64, false, 64), "takes all");
        assertEquals(new InventoryEditor.Move(3, 2, false), InventoryEditor.move(true, 0, 5, false, 64), "takes half");
        assertEquals(new InventoryEditor.Move(0, 10, false), InventoryEditor.move(false, 10, 0, false, 64), "puts all");
        assertEquals(new InventoryEditor.Move(9, 1, false), InventoryEditor.move(true, 10, 0, false, 64), "puts one");
        assertEquals(new InventoryEditor.Move(6, 64, false), InventoryEditor.move(false, 10, 60, true, 64), "fills up");
        assertEquals(new InventoryEditor.Move(10, 64, false), InventoryEditor.move(true, 10, 64, true, 64), "full");
        assertEquals(
                new InventoryEditor.Move(4, 16, false), InventoryEditor.move(false, 20, 0, false, 16), "a kind's max");
        assertEquals(new InventoryEditor.Move(5, 3, true), InventoryEditor.move(false, 3, 5, false, 64), "swaps");
    }

    @Test
    void aRefusedEditEvensOutByKindSoAMoveWithinTheWindowCostsNothing() {
        assertEquals(
                Map.of(),
                InventoryEditor.net(List.of(Map.entry("diamond", 64)), List.of(Map.entry("diamond", 64))),
                "64 diamonds moved from one slot to another");
        assertEquals(
                Map.of("diamond", -40, "dirt", 5),
                InventoryEditor.net(
                        List.of(Map.entry("diamond", 64), Map.entry("gold", 5)),
                        List.of(Map.entry("diamond", 24), Map.entry("gold", 5), Map.entry("dirt", 5))),
                "40 diamonds taken out, 5 gold moved and 5 dirt put in");
    }
}
