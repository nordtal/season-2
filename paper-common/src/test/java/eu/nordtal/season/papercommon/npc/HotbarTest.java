package eu.nordtal.season.papercommon.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Keys 1 to 3 pick an answer, the wheel moves the selection by one. */
class HotbarTest {

    @Test
    void aKeyPicksItsAnswer() {
        assertEquals(0, Hotbar.select(2, 3, 0));
        assertEquals(1, Hotbar.select(0, 3, 1));
        assertEquals(2, Hotbar.select(0, 3, 2));
    }

    @Test
    void aKeyWithoutAnAnswerChangesNothing() {
        assertEquals(1, Hotbar.select(1, 2, 2));
        assertEquals(1, Hotbar.select(1, 2, 8));
    }

    @Test
    void theWheelMovesUpAndDownAndStopsAtTheEnds() {
        assertEquals(1, Hotbar.select(0, 3, Hotbar.CHOICE_SLOT + 1));
        assertEquals(2, Hotbar.select(2, 3, Hotbar.CHOICE_SLOT + 1));
        assertEquals(1, Hotbar.select(2, 3, Hotbar.CHOICE_SLOT - 1));
        assertEquals(0, Hotbar.select(0, 3, Hotbar.CHOICE_SLOT - 1));
    }

    @Test
    void theHeldSlotItselfChangesNothing() {
        assertEquals(1, Hotbar.select(1, 3, Hotbar.CHOICE_SLOT));
    }
}
