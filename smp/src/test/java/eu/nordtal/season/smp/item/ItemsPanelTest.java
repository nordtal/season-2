package eu.nordtal.season.smp.item;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.event.inventory.ClickType;
import org.junit.jupiter.api.Test;

class ItemsPanelTest {

    @Test
    void pagerControlsAppearOnlyWhenItemsExceedSinglePageCapacity() {
        assertFalse(ItemsPanel.needsPaging(2));
        assertEquals(1, ItemsPanel.pageCount(2));
        assertEquals(1, ItemsPanel.rows(2));

        assertFalse(ItemsPanel.needsPaging(54));
        assertEquals(1, ItemsPanel.pageCount(54));
        assertEquals(6, ItemsPanel.rows(54));

        assertTrue(ItemsPanel.needsPaging(55));
        assertEquals(2, ItemsPanel.pageCount(55));
        assertEquals(6, ItemsPanel.rows(55));
        assertEquals(5, ItemsPanel.contentRows(55));
        assertNotNull(ItemsPanel.title("Nordtal items", 2, 0, "Previous", "Next"));
        assertNotNull(ItemsPanel.title("Nordtal items", 55, 0, "Previous", "Next"));
    }

    @Test
    void leftClickGivesOneAndShiftClickGivesMaxStackSize() {
        assertEquals(1, ItemsMenu.quantityFor(ClickType.LEFT, 64));
        assertEquals(64, ItemsMenu.quantityFor(ClickType.SHIFT_LEFT, 64));
        assertEquals(1, ItemsMenu.quantityFor(ClickType.SHIFT_LEFT, 1));
        assertEquals(64, ItemsMenu.quantityFor(ClickType.SHIFT_RIGHT, 64));
        assertEquals(0, ItemsMenu.quantityFor(ClickType.RIGHT, 64));
    }
}
