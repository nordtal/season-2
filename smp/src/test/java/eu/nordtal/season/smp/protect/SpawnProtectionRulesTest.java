package eu.nordtal.season.smp.protect;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.Material;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.junit.jupiter.api.Test;

class SpawnProtectionRulesTest {

    @Test
    void cushionsAreRecognisedAndOtherBlocksAreNot() {
        assertTrue(SpawnProtectionRules.isCushion(Material.RED_CUSHION));
        assertTrue(SpawnProtectionRules.isCushion(Material.WHITE_CUSHION));
        assertFalse(SpawnProtectionRules.isCushion(Material.RED_BED));
        assertFalse(SpawnProtectionRules.isCushion(Material.RED_WOOL));
    }

    @Test
    void localUtilityBlocksAllowStatelessWorkstationsAndExcludeAnvils() {
        assertTrue(SpawnProtectionRules.isLocalUtilityBlock(Material.ENDER_CHEST));
        assertTrue(SpawnProtectionRules.isLocalUtilityBlock(Material.CRAFTING_TABLE));
        assertTrue(SpawnProtectionRules.isLocalUtilityBlock(Material.ENCHANTING_TABLE));
        assertTrue(SpawnProtectionRules.isLocalUtilityBlock(Material.STONECUTTER));
        assertTrue(SpawnProtectionRules.isLocalUtilityBlock(Material.LOOM));
        assertTrue(SpawnProtectionRules.isLocalUtilityBlock(Material.CARTOGRAPHY_TABLE));
        assertTrue(SpawnProtectionRules.isLocalUtilityBlock(Material.GRINDSTONE));
        assertTrue(SpawnProtectionRules.isLocalUtilityBlock(Material.SMITHING_TABLE));

        assertFalse(SpawnProtectionRules.isLocalUtilityBlock(Material.ANVIL));
        assertFalse(SpawnProtectionRules.isLocalUtilityBlock(Material.CHIPPED_ANVIL));
        assertFalse(SpawnProtectionRules.isLocalUtilityBlock(Material.DAMAGED_ANVIL));
        assertFalse(SpawnProtectionRules.isLocalUtilityBlock(Material.CHEST));
    }

    @Test
    void handOpenableDoorsAndTrapdoorsAllowWoodAndCopperAndRejectIron() {
        assertTrue(SpawnProtectionRules.isHandOpenableDoor(Material.OAK_DOOR));
        assertTrue(SpawnProtectionRules.isHandOpenableDoor(Material.COPPER_DOOR));
        assertTrue(SpawnProtectionRules.isHandOpenableDoor(Material.WAXED_OXIDIZED_COPPER_DOOR));
        assertFalse(SpawnProtectionRules.isHandOpenableDoor(Material.IRON_DOOR));

        assertTrue(SpawnProtectionRules.isHandOpenableTrapdoor(Material.SPRUCE_TRAPDOOR));
        assertTrue(SpawnProtectionRules.isHandOpenableTrapdoor(Material.COPPER_TRAPDOOR));
        assertTrue(SpawnProtectionRules.isHandOpenableTrapdoor(Material.EXPOSED_COPPER_TRAPDOOR));
        assertFalse(SpawnProtectionRules.isHandOpenableTrapdoor(Material.IRON_TRAPDOOR));
    }

    @Test
    void disruptiveItemsAreBlockedWhileHarmlessItemsStayAllowed() {
        assertTrue(SpawnProtectionRules.isDisruptiveItem(Material.WIND_CHARGE, false, false));
        assertTrue(SpawnProtectionRules.isDisruptiveItem(Material.OAK_BOAT, true, false));
        assertTrue(SpawnProtectionRules.isDisruptiveItem(Material.BAMBOO_RAFT, true, false));
        assertTrue(SpawnProtectionRules.isDisruptiveItem(Material.TNT_MINECART, true, true));
        assertTrue(SpawnProtectionRules.isDisruptiveItem(Material.MINECART, true, false));
        assertFalse(SpawnProtectionRules.isDisruptiveItem(Material.MINECART, true, true));
        assertTrue(SpawnProtectionRules.isDisruptiveItem(Material.ARMOR_STAND, true, false));
        assertTrue(SpawnProtectionRules.isDisruptiveItem(Material.END_CRYSTAL, true, false));
        assertTrue(SpawnProtectionRules.isDisruptiveItem(Material.FLINT_AND_STEEL, true, false));
        assertTrue(SpawnProtectionRules.isDisruptiveItem(Material.FIRE_CHARGE, true, false));
        assertTrue(SpawnProtectionRules.isDisruptiveItem(Material.ZOMBIE_SPAWN_EGG, true, false));
        assertTrue(SpawnProtectionRules.isDisruptiveItem(Material.BONE_MEAL, true, false));
        assertTrue(SpawnProtectionRules.isDisruptiveItem(Material.SPLASH_POTION, false, false));
        assertTrue(SpawnProtectionRules.isDisruptiveItem(Material.LINGERING_POTION, false, false));
        assertTrue(SpawnProtectionRules.isDisruptiveItem(Material.DIAMOND_HOE, true, false));
        assertTrue(SpawnProtectionRules.isDisruptiveItem(Material.IRON_AXE, true, false));
        assertTrue(SpawnProtectionRules.isDisruptiveItem(Material.HONEYCOMB, true, false));

        assertFalse(SpawnProtectionRules.isDisruptiveItem(Material.COOKED_BEEF, true, false));
        assertFalse(SpawnProtectionRules.isDisruptiveItem(Material.POTION, true, false));
        assertFalse(SpawnProtectionRules.isDisruptiveItem(Material.EXPERIENCE_BOTTLE, true, false));
        assertFalse(SpawnProtectionRules.isDisruptiveItem(Material.ENDER_PEARL, true, false));
        assertFalse(SpawnProtectionRules.isDisruptiveItem(Material.CHORUS_FRUIT, true, false));
        assertFalse(SpawnProtectionRules.isDisruptiveItem(Material.SHIELD, true, false));
        assertFalse(SpawnProtectionRules.isDisruptiveItem(Material.SPYGLASS, true, false));
        assertFalse(SpawnProtectionRules.isDisruptiveItem(Material.FILLED_MAP, true, false));
    }

    @Test
    void projectileTriggerableBlocksAreMatched() {
        assertTrue(SpawnProtectionRules.isProjectileTriggerableBlock(Material.OAK_BUTTON));
        assertTrue(SpawnProtectionRules.isProjectileTriggerableBlock(Material.TARGET));
        assertTrue(SpawnProtectionRules.isProjectileTriggerableBlock(Material.BELL));
        assertTrue(SpawnProtectionRules.isProjectileTriggerableBlock(Material.WHITE_CANDLE));
        assertTrue(SpawnProtectionRules.isProjectileTriggerableBlock(Material.CAMPFIRE));
        assertTrue(SpawnProtectionRules.isProjectileTriggerableBlock(Material.STONE_PRESSURE_PLATE));
        assertFalse(SpawnProtectionRules.isProjectileTriggerableBlock(Material.STONE));
    }

    @Test
    void spontaneousMobSpawnsAreBlockedWhileExplicitPlacementIsAllowed() {
        assertTrue(SpawnProtectionRules.blocksCreatureSpawn(true, CreatureSpawnEvent.SpawnReason.NATURAL));
        assertTrue(SpawnProtectionRules.blocksCreatureSpawn(true, CreatureSpawnEvent.SpawnReason.SPAWNER));
        assertTrue(SpawnProtectionRules.blocksCreatureSpawn(true, CreatureSpawnEvent.SpawnReason.PATROL));
        assertTrue(SpawnProtectionRules.blocksCreatureSpawn(true, CreatureSpawnEvent.SpawnReason.RAID));
        assertFalse(SpawnProtectionRules.blocksCreatureSpawn(false, CreatureSpawnEvent.SpawnReason.NATURAL));

        assertFalse(SpawnProtectionRules.blocksCreatureSpawn(true, CreatureSpawnEvent.SpawnReason.CUSTOM));
        assertFalse(SpawnProtectionRules.blocksCreatureSpawn(true, CreatureSpawnEvent.SpawnReason.COMMAND));
        assertFalse(SpawnProtectionRules.blocksCreatureSpawn(true, CreatureSpawnEvent.SpawnReason.SPAWNER_EGG));
        assertFalse(SpawnProtectionRules.blocksCreatureSpawn(true, CreatureSpawnEvent.SpawnReason.BREEDING));
        assertFalse(SpawnProtectionRules.blocksCreatureSpawn(true, CreatureSpawnEvent.SpawnReason.BUCKET));
    }

    @Test
    void strayMobPathfindingAndTargetingIntoSpawnAreBlockedUnlessPlayerBrought() {
        assertTrue(SpawnProtectionRules.blocksMobPathfind(false, true, false, false, false, false));
        assertFalse(SpawnProtectionRules.blocksMobPathfind(true, true, false, false, false, false));
        assertFalse(SpawnProtectionRules.blocksMobPathfind(false, false, false, false, false, false));
        assertFalse(SpawnProtectionRules.blocksMobPathfind(false, true, true, false, false, false));
        assertFalse(SpawnProtectionRules.blocksMobPathfind(false, true, false, true, false, false));
        assertFalse(SpawnProtectionRules.blocksMobPathfind(false, true, false, false, true, false));
        assertFalse(SpawnProtectionRules.blocksMobPathfind(false, true, false, false, false, true));

        assertTrue(SpawnProtectionRules.blocksMobTarget(true, false, EntityTargetEvent.TargetReason.CLOSEST_PLAYER));
        assertFalse(SpawnProtectionRules.blocksMobTarget(false, false, EntityTargetEvent.TargetReason.CLOSEST_PLAYER));
        assertFalse(SpawnProtectionRules.blocksMobTarget(true, true, EntityTargetEvent.TargetReason.CLOSEST_PLAYER));
        assertFalse(SpawnProtectionRules.blocksMobTarget(true, false, EntityTargetEvent.TargetReason.TEMPT));
    }
}
