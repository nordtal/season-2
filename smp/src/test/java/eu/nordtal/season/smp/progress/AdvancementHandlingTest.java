package eu.nordtal.season.smp.progress;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AdvancementHandlingTest {

    @Test
    void anAdvancementIsPaidAndCredited() {
        assertFalse(AdvancementListener.isRecipe("minecraft:story/iron_tools"));
        assertFalse(AdvancementListener.isRecipe("minecraft:nether/obtain_blaze_rod"));
    }

    @Test
    void aRecipeIsIgnored() {
        assertTrue(AdvancementListener.isRecipe("minecraft:recipes/misc/iron_ingot"));
    }
}
