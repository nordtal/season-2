package eu.nordtal.season.smp.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
