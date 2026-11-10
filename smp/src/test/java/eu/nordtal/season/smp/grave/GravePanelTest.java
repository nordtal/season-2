package eu.nordtal.season.smp.grave;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

/** A grave grows to five rows of items, the action row right under them, and never pages. */
class GravePanelTest {

    @Test
    void theRowCountFollowsWhatWasCarried() {
        assertEquals(1, GravePanel.contentRows(0));
        assertEquals(1, GravePanel.contentRows(9));
        assertEquals(2, GravePanel.contentRows(10));
        assertEquals(4, GravePanel.contentRows(36));
        assertEquals(5, GravePanel.contentRows(41), "a full inventory with armour and offhand");
        assertEquals(5, GravePanel.contentRows(60));
    }

    @Test
    void theActionRowIsRightUnderTheItems() {
        assertEquals(2, GravePanel.rows(1));
        assertEquals(9, GravePanel.headSlot(1));
        assertEquals(List.of(13, 14, 15, 16, 17), GravePanel.takeAllSlots(1));
        assertEquals(6, GravePanel.rows(5));
        assertEquals(45, GravePanel.headSlot(5));
        assertEquals(List.of(49, 50, 51, 52, 53), GravePanel.takeAllSlots(5));
    }

    @Test
    void theHeadAndThePlateAreNoContent() {
        assertEquals(false, GravePanel.isContent(GravePanel.headSlot(2), 2));
        assertEquals(true, GravePanel.isContent(17, 2));
    }

    @Test
    void aGraveDrawsOneToFiveRowsOfItemsWithOrWithoutExperience() {
        for (int rows = 1; rows <= GravePanel.MAX_CONTENT_ROWS; rows++) {
            GravePanel.title("Grab", rows, "+120 XP", "Alles nehmen");
            GravePanel.title("Grab", rows, null, "Alles nehmen");
        }
        assertThrows(IllegalArgumentException.class, () -> GravePanel.title("Grab", 0, null, "Alles nehmen"));
        assertThrows(IllegalArgumentException.class, () -> GravePanel.title("Grab", 6, null, "Alles nehmen"));
    }
}
