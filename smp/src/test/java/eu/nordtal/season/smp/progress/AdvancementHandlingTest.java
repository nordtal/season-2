package eu.nordtal.season.smp.progress;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AdvancementHandlingTest {

    @Test
    void aGateAdvancementIsNoRecipe() {
        assertFalse(AdvancementListener.isRecipe("minecraft:story/iron_tools"));
        assertFalse(AdvancementListener.isRecipe("minecraft:story/mine_diamond"));
        assertFalse(AdvancementListener.isRecipe("minecraft:nether/obtain_blaze_rod"));
        assertFalse(AdvancementListener.isRecipe("minecraft:nether/netherite_armor"));
    }

    @Test
    void aRecipeIsIgnored() {
        assertTrue(AdvancementListener.isRecipe("minecraft:recipes/misc/iron_ingot"));
    }
}
