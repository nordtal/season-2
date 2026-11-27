package eu.nordtal.season.smp.tunnel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.Material;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

class TunnelMaterialsTest {

    @Test
    void unbreakableUtilityAndContainerBlocksAreSkippedWhileNaturalBlocksMine() {
        assertTrue(TunnelMaterials.isSkipped(Material.BEDROCK, -1f, false));
        assertTrue(TunnelMaterials.isSkipped(Material.OBSIDIAN, 50f, false));
        assertTrue(TunnelMaterials.isSkipped(Material.REINFORCED_DEEPSLATE, 55f, false));
        assertTrue(TunnelMaterials.isSkipped(Material.SPAWNER, 5f, false));
        assertTrue(TunnelMaterials.isSkipped(Material.ENDER_CHEST, 22.5f, false));
        assertTrue(TunnelMaterials.isSkipped(Material.CHEST, 2.5f, true));
        assertFalse(TunnelMaterials.isSkipped(Material.STONE, 1.5f, false));
        assertFalse(TunnelMaterials.isSkipped(Material.DIRT, 0.5f, false));
        assertFalse(TunnelMaterials.isSkipped(Material.GRAVEL, 0.6f, false));
    }

    @Test
    void stairSelectionFollowsWallThenCeilingThenDominantRockThenDimensionFallback() {
        assertEquals(
                Material.ANDESITE_STAIRS,
                TunnelMaterials.pickStair(
                        Material.ANDESITE, Material.DIORITE, Material.STONE, World.Environment.NORMAL, 40));
        assertEquals(
                Material.DIORITE_STAIRS,
                TunnelMaterials.pickStair(
                        Material.DIRT, Material.DIORITE, Material.STONE, World.Environment.NORMAL, 40));
        assertEquals(
                Material.COBBLED_DEEPSLATE_STAIRS,
                TunnelMaterials.pickStair(
                        Material.DIRT, Material.AIR, Material.DEEPSLATE, World.Environment.NORMAL, 40));
        assertEquals(
                Material.STONE_STAIRS,
                TunnelMaterials.pickStair(Material.DIRT, Material.AIR, null, World.Environment.NORMAL, 10));
        assertEquals(
                Material.COBBLED_DEEPSLATE_STAIRS,
                TunnelMaterials.pickStair(Material.DIRT, Material.AIR, null, World.Environment.NORMAL, -20));
        assertEquals(
                Material.BLACKSTONE_STAIRS,
                TunnelMaterials.pickStair(Material.AIR, Material.AIR, null, World.Environment.NETHER, 60));
        assertEquals(
                Material.END_STONE_BRICK_STAIRS,
                TunnelMaterials.pickStair(Material.AIR, Material.AIR, null, World.Environment.THE_END, 60));
    }

    @Test
    void speedMultiplierScalesTotalBreakTimeAndClampsAtTargetedBlockTime() {
        // 50 identical blocks with factor 0.08 => 4x target break time => 0.25x speed multiplier.
        assertEquals(0.25, TunnelMaterials.speedMultiplier(10.0, 500.0, 0.08), 1e-6);
        // Very few breakable blocks never speed up past the targeted block's normal break time.
        assertEquals(1.0, TunnelMaterials.speedMultiplier(10.0, 20.0, 0.08), 1e-6);
    }

    @Test
    void baseRockClassificationMatchesWorldEnvironmentAndExcludesOresAndProcessedVariants() {
        for (final Material rock : new Material[] {
            Material.STONE, Material.DEEPSLATE, Material.ANDESITE, Material.DIORITE, Material.GRANITE, Material.TUFF
        }) {
            assertTrue(TunnelMaterials.isReducedDropRock(rock, World.Environment.NORMAL));
            assertFalse(TunnelMaterials.isReducedDropRock(rock, World.Environment.NETHER));
            assertFalse(TunnelMaterials.isReducedDropRock(rock, World.Environment.THE_END));
        }
        for (final Material rock : new Material[] {Material.NETHERRACK, Material.BLACKSTONE, Material.BASALT}) {
            assertTrue(TunnelMaterials.isReducedDropRock(rock, World.Environment.NETHER));
            assertFalse(TunnelMaterials.isReducedDropRock(rock, World.Environment.NORMAL));
            assertFalse(TunnelMaterials.isReducedDropRock(rock, World.Environment.THE_END));
        }
        assertTrue(TunnelMaterials.isReducedDropRock(Material.END_STONE, World.Environment.THE_END));
        assertFalse(TunnelMaterials.isReducedDropRock(Material.END_STONE, World.Environment.NORMAL));

        for (final Material unchanged : new Material[] {
            Material.COAL_ORE,
            Material.IRON_ORE,
            Material.GOLD_ORE,
            Material.DIAMOND_ORE,
            Material.DEEPSLATE_DIAMOND_ORE,
            Material.NETHER_GOLD_ORE,
            Material.NETHER_QUARTZ_ORE,
            Material.ANCIENT_DEBRIS,
            Material.COBBLESTONE,
            Material.COBBLED_DEEPSLATE,
            Material.STONE_BRICKS,
            Material.POLISHED_ANDESITE,
            Material.SMOOTH_BASALT,
            Material.STONE_STAIRS,
            Material.END_STONE_BRICKS,
            Material.DIRT,
            Material.GRAVEL
        }) {
            assertFalse(TunnelMaterials.isReducedDropRock(unchanged, World.Environment.NORMAL));
            assertFalse(TunnelMaterials.isReducedDropRock(unchanged, World.Environment.NETHER));
            assertFalse(TunnelMaterials.isReducedDropRock(unchanged, World.Environment.THE_END));
        }
    }

    @Test
    void baseRockRollDropsBelowHalfAndOresAlwaysDrop() {
        assertTrue(TunnelMaterials.shouldDropInTunnel(Material.STONE, World.Environment.NORMAL, 0.0));
        assertTrue(TunnelMaterials.shouldDropInTunnel(Material.STONE, World.Environment.NORMAL, 0.499999));
        assertFalse(TunnelMaterials.shouldDropInTunnel(Material.STONE, World.Environment.NORMAL, 0.5));
        assertFalse(TunnelMaterials.shouldDropInTunnel(Material.STONE, World.Environment.NORMAL, 0.999999));
        assertFalse(TunnelMaterials.shouldDropInTunnel(Material.NETHERRACK, World.Environment.NETHER, 0.5));
        assertFalse(TunnelMaterials.shouldDropInTunnel(Material.END_STONE, World.Environment.THE_END, 0.5));

        assertTrue(TunnelMaterials.shouldDropInTunnel(Material.DIAMOND_ORE, World.Environment.NORMAL, 0.999999));
        assertTrue(
                TunnelMaterials.shouldDropInTunnel(Material.DEEPSLATE_DIAMOND_ORE, World.Environment.NORMAL, 0.999999));
        assertTrue(TunnelMaterials.shouldDropInTunnel(Material.NETHER_QUARTZ_ORE, World.Environment.NETHER, 0.999999));
        assertTrue(TunnelMaterials.shouldDropInTunnel(Material.STONE_BRICKS, World.Environment.NORMAL, 0.999999));
    }
}
