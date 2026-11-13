package eu.nordtal.season.smp.progress;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.smp.progress.AdvancementListener.Handling;
import org.junit.jupiter.api.Test;

class AdvancementHandlingTest {

    @Test
    void anAdvancementOutsideADuelIsPaidAndCredited() {
        assertEquals(Handling.PAY, AdvancementListener.handlingOf("minecraft:story/obtain_armor", false));
    }

    @Test
    void anAdvancementEarnedInADuelIsSilencedAndPaysNothing() {
        assertEquals(Handling.SILENCE, AdvancementListener.handlingOf("minecraft:story/obtain_armor", true));
    }

    @Test
    void aRecipeIsIgnoredInAndOutsideADuel() {
        assertEquals(Handling.IGNORE, AdvancementListener.handlingOf("minecraft:recipes/misc/iron_ingot", false));
        assertEquals(Handling.IGNORE, AdvancementListener.handlingOf("minecraft:recipes/misc/iron_ingot", true));
    }
}
