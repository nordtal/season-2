package eu.nordtal.season.underground.block;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BlocksTest {

    private final Blocks blocks = Blocks.load();

    @Test
    void aBlockOfTheMapOnNoListCountsAsBuilt() {
        assertEquals(Blocks.BUILT, blocks.mapCell("minecraft:oak_planks") & Blocks.KIND);
        assertEquals(Blocks.BUILT, blocks.mapCell("minecraft:spawner") & Blocks.KIND);
        assertEquals(Blocks.BUILT, blocks.mapCell("minecraft:end_portal_frame") & Blocks.KIND);
    }

    @Test
    void groundPlantsAndFluidsAreNotBuilt() {
        assertEquals(Blocks.ROCK, blocks.mapCell("minecraft:stone") & Blocks.KIND);
        assertEquals(Blocks.LOOSE, blocks.mapCell("minecraft:oak_leaves") & Blocks.KIND);
        assertEquals(Blocks.WATER, blocks.mapCell("minecraft:water") & Blocks.KIND);
        assertEquals(Blocks.AMBIGUOUS, blocks.mapCell("minecraft:cobblestone") & Blocks.KIND);
        assertTrue(Blocks.open(blocks.mapCell("minecraft:cave_air")));
        assertFalse(Blocks.open(blocks.mapCell("minecraft:deepslate")));
    }

    @Test
    void theTerracottaTheMapUsesAsGroundIsBuiltOnlyNearABuilding() {
        assertEquals(Blocks.AMBIGUOUS, blocks.mapCell("minecraft:terracotta") & Blocks.KIND);
        assertEquals(Blocks.AMBIGUOUS, blocks.mapCell("minecraft:cyan_terracotta") & Blocks.KIND);
        assertEquals(Blocks.AMBIGUOUS, blocks.mapCell("minecraft:light_gray_terracotta") & Blocks.KIND);
    }

    @Test
    void nothingOfTheGeneratedWorldCountsAsBuilt() {
        assertEquals(Blocks.LOOSE, blocks.parallelCell("minecraft:oak_planks") & Blocks.KIND);
        assertEquals(Blocks.ROCK, blocks.parallelCell("minecraft:deepslate") & Blocks.KIND);
    }

    @Test
    void aFilledCellTakesTheRockItRemembersOrTheRockOfItsDepth() {
        assertEquals("minecraft:granite", Blocks.filling(blocks.mapCell("minecraft:granite"), 40));
        assertEquals("minecraft:stone", Blocks.filling(blocks.mapCell("minecraft:iron_ore"), 40));
        assertEquals("minecraft:deepslate", Blocks.filling(blocks.mapCell("minecraft:iron_ore"), -20));
    }
}
