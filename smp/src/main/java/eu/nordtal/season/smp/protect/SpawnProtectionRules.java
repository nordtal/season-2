package eu.nordtal.season.smp.protect;

import java.util.EnumSet;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityTargetEvent;

/** Pure decision functions for spawn protection rules, tested without a running server. */
final class SpawnProtectionRules {

    private static final Set<Material> LOCAL_UTILITY_BLOCKS = EnumSet.of(
            Material.ENDER_CHEST,
            Material.CRAFTING_TABLE,
            Material.ENCHANTING_TABLE,
            Material.STONECUTTER,
            Material.LOOM,
            Material.CARTOGRAPHY_TABLE,
            Material.GRINDSTONE,
            Material.SMITHING_TABLE);

    private static final Set<CreatureSpawnEvent.SpawnReason> ALLOWED_SPAWN_REASONS = EnumSet.of(
            CreatureSpawnEvent.SpawnReason.CUSTOM,
            CreatureSpawnEvent.SpawnReason.COMMAND,
            CreatureSpawnEvent.SpawnReason.SPAWNER_EGG,
            CreatureSpawnEvent.SpawnReason.BREEDING,
            CreatureSpawnEvent.SpawnReason.EGG,
            CreatureSpawnEvent.SpawnReason.BUCKET,
            CreatureSpawnEvent.SpawnReason.SHOULDER_ENTITY,
            CreatureSpawnEvent.SpawnReason.MOUNT,
            CreatureSpawnEvent.SpawnReason.CURED,
            CreatureSpawnEvent.SpawnReason.BUILD_IRONGOLEM,
            CreatureSpawnEvent.SpawnReason.BUILD_SNOWMAN,
            CreatureSpawnEvent.SpawnReason.BUILD_COPPERGOLEM,
            CreatureSpawnEvent.SpawnReason.BUILD_WITHER,
            CreatureSpawnEvent.SpawnReason.SHEARED,
            CreatureSpawnEvent.SpawnReason.DUPLICATION,
            CreatureSpawnEvent.SpawnReason.METAMORPHOSIS,
            CreatureSpawnEvent.SpawnReason.REHYDRATION,
            CreatureSpawnEvent.SpawnReason.DEFAULT);

    private SpawnProtectionRules() {}

    /** Returns true for vanilla cushion blocks ({@code *_CUSHION}). */
    static boolean isCushion(final Material material) {
        return material.name().endsWith("_CUSHION");
    }

    /** Returns true for local utility blocks that do not alter world state. Anvils are excluded. */
    static boolean isLocalUtilityBlock(final Material material) {
        return LOCAL_UTILITY_BLOCKS.contains(material);
    }

    /** Returns true for hand-openable doors (wood and copper), rejecting iron doors. */
    static boolean isHandOpenableDoor(final Material material) {
        final String name = material.name();
        return name.endsWith("_DOOR") && material != Material.IRON_DOOR;
    }

    /** Returns true for hand-openable trapdoors (wood and copper), rejecting iron trapdoors. */
    static boolean isHandOpenableTrapdoor(final Material material) {
        final String name = material.name();
        return name.endsWith("_TRAPDOOR") && material != Material.IRON_TRAPDOOR;
    }

    /** Returns true for hand-openable doors and trapdoors (wood and copper, excluding iron). */
    static boolean isHandOpenableDoorOrTrapdoor(final Material material) {
        return isHandOpenableDoor(material) || isHandOpenableTrapdoor(material);
    }

    /**
     * Returns true for disruptive or world-altering items used inside spawn.
     * Harmless items (food, drinks, XP bottles, ender pearls, chorus fruit, shields, spyglass, maps) return false.
     */
    static boolean isDisruptiveItem(final Material material, final boolean clickedBlock, final boolean onNetworkRail) {
        final String name = material.name();
        if (material == Material.WIND_CHARGE
                || material == Material.ARMOR_STAND
                || material == Material.END_CRYSTAL
                || material == Material.FLINT_AND_STEEL
                || material == Material.FIRE_CHARGE
                || material == Material.BONE_MEAL
                || material == Material.SPLASH_POTION
                || material == Material.LINGERING_POTION
                || name.endsWith("_SPAWN_EGG")
                || name.endsWith("_BOAT")
                || name.endsWith("_CHEST_BOAT")
                || name.endsWith("_RAFT")
                || name.endsWith("_CHEST_RAFT")) {
            return true;
        }
        if (material == Material.MINECART) {
            return !onNetworkRail;
        }
        if (name.endsWith("_MINECART")) {
            return true;
        }
        if (!clickedBlock) {
            return false;
        }
        return name.endsWith("_HOE")
                || name.endsWith("_AXE")
                || name.endsWith("_SHOVEL")
                || material == Material.HONEYCOMB
                || material == Material.BRUSH;
    }

    /** Returns true for blocks that projectiles can trigger or alter inside spawn. */
    static boolean isProjectileTriggerableBlock(final Material material) {
        final String name = material.name();
        return material == Material.TARGET
                || material == Material.BELL
                || material == Material.TNT
                || material == Material.CHORUS_FLOWER
                || material == Material.DECORATED_POT
                || material == Material.TRIPWIRE
                || material == Material.TRIPWIRE_HOOK
                || material == Material.CAMPFIRE
                || material == Material.SOUL_CAMPFIRE
                || name.endsWith("_BUTTON")
                || name.endsWith("_PRESSURE_PLATE")
                || name.endsWith("CANDLE")
                || name.endsWith("CANDLE_CAKE");
    }

    /** Returns true when a creature spawn inside spawn should be blocked. */
    static boolean blocksCreatureSpawn(final boolean insideSpawn, final CreatureSpawnEvent.SpawnReason reason) {
        return insideSpawn && !ALLOWED_SPAWN_REASONS.contains(reason);
    }

    /** Returns true when a mob outside spawn should be prevented from pathfinding into spawn. */
    static boolean blocksMobPathfind(
            final boolean entityInSpawn,
            final boolean targetInSpawn,
            final boolean leashed,
            final boolean inVehicle,
            final boolean tamed,
            final boolean luredByPlayer) {
        return !entityInSpawn && targetInSpawn && !leashed && !inVehicle && !tamed && !luredByPlayer;
    }

    /** Returns true when a mob targeting a player inside spawn should be blocked. */
    static boolean blocksMobTarget(
            final boolean targetInSpawn, final boolean tamed, final EntityTargetEvent.TargetReason reason) {
        return targetInSpawn
                && !tamed
                && reason != EntityTargetEvent.TargetReason.TEMPT
                && reason != EntityTargetEvent.TargetReason.FOLLOW_LEADER;
    }
}
