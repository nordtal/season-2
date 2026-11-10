package eu.nordtal.season.smp.wheel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The wheel keeps its ring of twelve prizes and its "again" plate on the chest panel. */
class WheelPanelTest {

    @Test
    void theRingIsTwelveCellsClockwiseFromTheWinnersUnderThePointer() {
        assertEquals(12, new HashSet<>(WheelPanel.CELL_SLOTS).size());
        assertEquals(11, WheelPanel.CELL_SLOTS.getFirst(), "the winner rests in column 2 of row 1");
        assertEquals(12, WheelPanel.shape().count());
    }

    @Test
    void thePlateTakesTheLastFourCellsOfTheLastRowBesideTheRing() {
        assertEquals(List.of(32, 33, 34, 35), WheelPanel.AGAIN_SLOTS);
        assertTrue(WheelPanel.AGAIN_SLOTS.stream().noneMatch(WheelPanel.CELL_SLOTS::contains));
    }

    @Test
    void theWheelDrawsWithAndWithoutASpinLeft() {
        WheelPanel.title("Glücksrad", "Noch 2 Spins", "Nochmal", true);
        WheelPanel.title("Glücksrad", "Keine Spins", "Nochmal", false);
    }
}
