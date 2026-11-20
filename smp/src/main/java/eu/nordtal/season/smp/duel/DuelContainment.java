package eu.nordtal.season.smp.duel;

import java.util.EnumSet;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.event.block.Action;

/** Decides which movements and item hand-offs a duel refuses. */
final class DuelContainment {

    private static final Set<Material> BLOCKS_TAKING_HELD_ITEM = EnumSet.of(
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
            Material.SOUL_CAMPFIRE);

    private DuelContainment() {}

    static boolean leavesArena(final boolean fights, final boolean ownTeleport, final boolean inside) {
        return fights && !ownTeleport && !inside;
    }

    static boolean refusesInventory(final boolean fights, final boolean own) {
        return fights && !own;
    }

    static boolean refusesEntityHandOff(final boolean fights, final boolean hasHeldItem) {
        return fights && hasHeldItem;
    }

    static boolean refusesMount(final boolean fights) {
        return fights;
    }

    static boolean refusesSpawnEgg(final boolean fights, final Action action, final Material item) {
        return fights && action == Action.RIGHT_CLICK_BLOCK && item.name().endsWith("_SPAWN_EGG");
    }

    static boolean refusesBlockPlacement(final boolean fights, final boolean inside) {
        return fights && !inside;
    }

    static boolean refusesBlockHandOff(
            final boolean fights, final boolean hasHeldItem, final Action action, final Material block) {
        return fights && hasHeldItem && action == Action.RIGHT_CLICK_BLOCK && BLOCKS_TAKING_HELD_ITEM.contains(block);
    }
}
