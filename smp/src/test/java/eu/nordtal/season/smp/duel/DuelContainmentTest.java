package eu.nordtal.season.smp.duel;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.bukkit.Material;
import org.bukkit.event.block.Action;
import org.junit.jupiter.api.Test;

class DuelContainmentTest {

    @Test
    void aFighterOutsideTheArenaLosesAndOneInsideDoesNot() {
        assertTrue(DuelContainment.leavesArena(true, false, false));
        assertFalse(DuelContainment.leavesArena(true, false, true));
        assertFalse(DuelContainment.leavesArena(false, false, false));
    }

    @Test
    void theDuelsOwnTeleportOutsideTheArenaDecidesNothing() {
        assertFalse(DuelContainment.leavesArena(true, true, false));
    }

    @Test
    void aFighterOpensOnlyTheirOwnInventory() {
        assertTrue(DuelContainment.refusesInventory(true, false));
        assertFalse(DuelContainment.refusesInventory(true, true));
        assertFalse(DuelContainment.refusesInventory(false, false));
    }

    @Test
    void aFighterHandsNoHeldItemToAnEntity() {
        assertTrue(DuelContainment.refusesEntityHandOff(true, true));
        assertFalse(DuelContainment.refusesEntityHandOff(true, false));
        assertFalse(DuelContainment.refusesEntityHandOff(false, true));
    }

    @Test
    void aFighterHandsNoHeldItemToABlockThatStoresOrConsumesIt() {
        final List<Material> blocks = List.of(
                Material.CAKE,
                Material.CAMPFIRE,
                Material.CHISELED_BOOKSHELF,
                Material.COMPOSTER,
                Material.DECORATED_POT,
                Material.END_PORTAL_FRAME,
                Material.FLOWER_POT,
                Material.JUKEBOX,
                Material.LECTERN,
                Material.RESPAWN_ANCHOR,
                Material.SOUL_CAMPFIRE,
                Material.VAULT,
                Material.OAK_SHELF,
                Material.PALE_OAK_SHELF,
                Material.WARPED_SHELF);
        for (final Material block : blocks) {
            assertTrue(DuelContainment.refusesBlockHandOff(true, true, Action.RIGHT_CLICK_BLOCK, block), block::name);
        }
    }

    @Test
    void aBlockOtherThanAShelfIsRefusedOnlyForAFightersRightClickWithAHeldItem() {
        assertFalse(DuelContainment.refusesBlockHandOff(false, true, Action.RIGHT_CLICK_BLOCK, Material.DECORATED_POT));
        assertFalse(DuelContainment.refusesBlockHandOff(true, false, Action.RIGHT_CLICK_BLOCK, Material.DECORATED_POT));
        assertFalse(DuelContainment.refusesBlockHandOff(true, true, Action.LEFT_CLICK_BLOCK, Material.DECORATED_POT));
    }

    @Test
    void aFighterUsesNoShelfEvenWithAnEmptyHand() {
        assertTrue(DuelContainment.refusesBlockHandOff(true, false, Action.RIGHT_CLICK_BLOCK, Material.OAK_SHELF));
        assertFalse(DuelContainment.refusesBlockHandOff(false, false, Action.RIGHT_CLICK_BLOCK, Material.OAK_SHELF));
        assertFalse(
                DuelContainment.refusesBlockHandOff(true, false, Action.RIGHT_CLICK_BLOCK, Material.SHELF_MUSHROOM));
    }

    @Test
    void aFighterMountsNoEntity() {
        assertTrue(DuelContainment.refusesMount(true));
        assertFalse(DuelContainment.refusesMount(false));
    }

    @Test
    void aFighterSpawnsNoMobFromAKitEgg() {
        assertTrue(DuelContainment.refusesSpawnEgg(true, Action.RIGHT_CLICK_BLOCK, Material.CREEPER_SPAWN_EGG));
        assertTrue(DuelContainment.refusesSpawnEgg(true, Action.RIGHT_CLICK_AIR, Material.AXOLOTL_SPAWN_EGG));
        assertFalse(DuelContainment.refusesSpawnEgg(false, Action.RIGHT_CLICK_BLOCK, Material.CREEPER_SPAWN_EGG));
        assertFalse(DuelContainment.refusesSpawnEgg(true, Action.LEFT_CLICK_BLOCK, Material.CREEPER_SPAWN_EGG));
        assertFalse(DuelContainment.refusesSpawnEgg(true, Action.RIGHT_CLICK_BLOCK, Material.STONE));
    }

    @Test
    void aFighterPlacesNoBlockOutsideTheArena() {
        assertTrue(DuelContainment.refusesBlockPlacement(true, false));
        assertFalse(DuelContainment.refusesBlockPlacement(true, true));
        assertFalse(DuelContainment.refusesBlockPlacement(false, false));
    }

    @Test
    void brushingAndOrdinaryBlockUseStayAllowed() {
        assertFalse(
                DuelContainment.refusesBlockHandOff(true, true, Action.RIGHT_CLICK_BLOCK, Material.SUSPICIOUS_SAND));
        assertFalse(
                DuelContainment.refusesBlockHandOff(true, true, Action.RIGHT_CLICK_BLOCK, Material.SUSPICIOUS_GRAVEL));
        assertFalse(DuelContainment.refusesBlockHandOff(true, true, Action.RIGHT_CLICK_BLOCK, Material.STONE));
        assertFalse(DuelContainment.refusesBlockHandOff(true, true, Action.RIGHT_CLICK_BLOCK, Material.BOOKSHELF));
    }
}
