package eu.nordtal.season.smp.tunnel;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.World;
import org.jspecify.annotations.Nullable;

/**
 * Pure material rules for Tunnel Miner: skipped blocks, stair/support material matching and speed scaling.
 */
public final class TunnelMaterials {

    private static final Set<Material> SKIPPED = EnumSet.of(
            Material.REINFORCED_DEEPSLATE,
            Material.OBSIDIAN,
            Material.CRYING_OBSIDIAN,
            Material.RESPAWN_ANCHOR,
            Material.SPAWNER,
            Material.TRIAL_SPAWNER,
            Material.VAULT,
            Material.BUDDING_AMETHYST,
            Material.ENDER_CHEST,
            Material.BEACON,
            Material.ENCHANTING_TABLE,
            Material.ANVIL,
            Material.CHIPPED_ANVIL,
            Material.DAMAGED_ANVIL,
            Material.LODESTONE,
            Material.CONDUIT,
            Material.DRAGON_EGG,
            Material.JUKEBOX,
            Material.LECTERN,
            Material.CHISELED_BOOKSHELF,
            Material.DECORATED_POT);

    private static final Set<Material> OVERWORLD_BASE_ROCK = EnumSet.of(
            Material.STONE, Material.DEEPSLATE, Material.ANDESITE, Material.DIORITE, Material.GRANITE, Material.TUFF);

    private static final Set<Material> NETHER_BASE_ROCK =
            EnumSet.of(Material.NETHERRACK, Material.BLACKSTONE, Material.BASALT);

    private static final Set<Material> END_BASE_ROCK = EnumSet.of(Material.END_STONE);

    /** Probability that a natural base-rock block produces its normal drop during an automatic tunnel step. */
    public static final double BASE_ROCK_DROP_CHANCE = 0.5;

    private static final Map<Material, Material> ROCK_TO_STAIR = buildStairMap();
    private static final Map<Material, Material> ROCK_TO_SUPPORT = buildSupportMap();

    private TunnelMaterials() {}

    /** Returns whether {@code type} is a natural base-rock block subject to reduced drops in {@code environment}. */
    public static boolean isReducedDropRock(final Material type, final World.Environment environment) {
        return switch (environment) {
            case NORMAL -> OVERWORLD_BASE_ROCK.contains(type);
            case NETHER -> NETHER_BASE_ROCK.contains(type);
            case THE_END -> END_BASE_ROCK.contains(type);
            default -> false;
        };
    }

    /** Returns whether an automatic step drops {@code type} in {@code environment} for {@code roll}. */
    public static boolean shouldDropInTunnel(
            final Material type, final World.Environment environment, final double roll) {
        if (!isReducedDropRock(type, environment)) {
            return true;
        }
        return roll < BASE_ROCK_DROP_CHANCE;
    }

    /** Returns whether a block of {@code type} (with {@code hardness} and container flag) must be skipped. */
    public static boolean isSkipped(final Material type, final float hardness, final boolean inventoryContainer) {
        return hardness < 0f || inventoryContainer || SKIPPED.contains(type);
    }

    /** Returns whether a block of {@code type} must be skipped, using Bukkit's hardness metadata. */
    public static boolean isSkipped(final Material type, final boolean inventoryContainer) {
        return isSkipped(type, type.getHardness(), inventoryContainer);
    }

    /** Returns the stair material matching {@code rock}, or null if {@code rock} has no matching stair. */
    public static @Nullable Material stairFor(final Material rock) {
        return ROCK_TO_STAIR.get(rock);
    }

    /** Returns the solid support block matching {@code rock}, or null if {@code rock} has no solid support. */
    public static @Nullable Material supportFor(final Material rock) {
        return ROCK_TO_SUPPORT.get(rock);
    }

    /** Returns the dimension and Y-level fallback stair material. */
    public static Material fallbackStair(final World.Environment environment, final int y) {
        return switch (environment) {
            case NETHER -> Material.BLACKSTONE_STAIRS;
            case THE_END -> Material.END_STONE_BRICK_STAIRS;
            default -> y < 0 ? Material.COBBLED_DEEPSLATE_STAIRS : Material.STONE_STAIRS;
        };
    }

    /** Returns the dimension and Y-level fallback solid support block. */
    public static Material fallbackSupport(final World.Environment environment, final int y) {
        return switch (environment) {
            case NETHER -> Material.BLACKSTONE;
            case THE_END -> Material.END_STONE_BRICKS;
            default -> y < 0 ? Material.COBBLED_DEEPSLATE : Material.STONE;
        };
    }

    /** Picks the ceiling stair material: side wall, ceiling, dominant rock, then dimension/Y fallback. */
    public static Material pickStair(
            final Material sideWall,
            final Material ceiling,
            final @Nullable Material dominantRock,
            final World.Environment environment,
            final int y) {
        final Material fromWall = stairFor(sideWall);
        if (fromWall != null) {
            return fromWall;
        }
        final Material fromCeiling = stairFor(ceiling);
        if (fromCeiling != null) {
            return fromCeiling;
        }
        if (dominantRock != null) {
            final Material fromDominant = stairFor(dominantRock);
            if (fromDominant != null) {
                return fromDominant;
            }
        }
        return fallbackStair(environment, y);
    }

    /** Picks the solid support block under a rail: dominant step rock if mappable, else dimension/Y fallback. */
    public static Material pickSupport(
            final @Nullable Material dominantRock, final World.Environment environment, final int y) {
        if (dominantRock != null) {
            final Material fromDominant = supportFor(dominantRock);
            if (fromDominant != null) {
                return fromDominant;
            }
        }
        return fallbackSupport(environment, y);
    }

    /** Computes the {@code BLOCK_BREAK_SPEED} multiplier in {@code (0, 1]} for the upcoming tunnel step. */
    public static double speedMultiplier(
            final double targetBreakTime, final double totalStepBreakTime, final double timeFactor) {
        if (targetBreakTime <= 0.0 || totalStepBreakTime <= 0.0 || timeFactor <= 0.0) {
            return 1.0;
        }
        final double desired = Math.max(targetBreakTime, totalStepBreakTime * timeFactor);
        return Math.max(1e-4, Math.min(1.0, targetBreakTime / desired));
    }

    private static Map<Material, Material> buildStairMap() {
        final Map<Material, Material> map = new EnumMap<>(Material.class);
        map.put(Material.STONE, Material.STONE_STAIRS);
        map.put(Material.COBBLESTONE, Material.COBBLESTONE_STAIRS);
        map.put(Material.MOSSY_COBBLESTONE, Material.MOSSY_COBBLESTONE_STAIRS);
        map.put(Material.STONE_BRICKS, Material.STONE_BRICK_STAIRS);
        map.put(Material.MOSSY_STONE_BRICKS, Material.MOSSY_STONE_BRICK_STAIRS);
        map.put(Material.DEEPSLATE, Material.COBBLED_DEEPSLATE_STAIRS);
        map.put(Material.COBBLED_DEEPSLATE, Material.COBBLED_DEEPSLATE_STAIRS);
        map.put(Material.POLISHED_DEEPSLATE, Material.POLISHED_DEEPSLATE_STAIRS);
        map.put(Material.DEEPSLATE_BRICKS, Material.DEEPSLATE_BRICK_STAIRS);
        map.put(Material.DEEPSLATE_TILES, Material.DEEPSLATE_TILE_STAIRS);
        map.put(Material.ANDESITE, Material.ANDESITE_STAIRS);
        map.put(Material.POLISHED_ANDESITE, Material.POLISHED_ANDESITE_STAIRS);
        map.put(Material.DIORITE, Material.DIORITE_STAIRS);
        map.put(Material.POLISHED_DIORITE, Material.POLISHED_DIORITE_STAIRS);
        map.put(Material.GRANITE, Material.GRANITE_STAIRS);
        map.put(Material.POLISHED_GRANITE, Material.POLISHED_GRANITE_STAIRS);
        map.put(Material.TUFF, Material.TUFF_STAIRS);
        map.put(Material.POLISHED_TUFF, Material.POLISHED_TUFF_STAIRS);
        map.put(Material.TUFF_BRICKS, Material.TUFF_BRICK_STAIRS);
        map.put(Material.SANDSTONE, Material.SANDSTONE_STAIRS);
        map.put(Material.SMOOTH_SANDSTONE, Material.SMOOTH_SANDSTONE_STAIRS);
        map.put(Material.RED_SANDSTONE, Material.RED_SANDSTONE_STAIRS);
        map.put(Material.SMOOTH_RED_SANDSTONE, Material.SMOOTH_RED_SANDSTONE_STAIRS);
        map.put(Material.PRISMARINE, Material.PRISMARINE_STAIRS);
        map.put(Material.PRISMARINE_BRICKS, Material.PRISMARINE_BRICK_STAIRS);
        map.put(Material.DARK_PRISMARINE, Material.DARK_PRISMARINE_STAIRS);
        map.put(Material.NETHERRACK, Material.BLACKSTONE_STAIRS);
        map.put(Material.BLACKSTONE, Material.BLACKSTONE_STAIRS);
        map.put(Material.POLISHED_BLACKSTONE, Material.POLISHED_BLACKSTONE_STAIRS);
        map.put(Material.POLISHED_BLACKSTONE_BRICKS, Material.POLISHED_BLACKSTONE_BRICK_STAIRS);
        map.put(Material.NETHER_BRICKS, Material.NETHER_BRICK_STAIRS);
        map.put(Material.RED_NETHER_BRICKS, Material.RED_NETHER_BRICK_STAIRS);
        map.put(Material.QUARTZ_BLOCK, Material.QUARTZ_STAIRS);
        map.put(Material.SMOOTH_QUARTZ, Material.SMOOTH_QUARTZ_STAIRS);
        map.put(Material.END_STONE, Material.END_STONE_BRICK_STAIRS);
        map.put(Material.END_STONE_BRICKS, Material.END_STONE_BRICK_STAIRS);
        map.put(Material.PURPUR_BLOCK, Material.PURPUR_STAIRS);
        map.put(Material.BRICKS, Material.BRICK_STAIRS);
        map.put(Material.MUD_BRICKS, Material.MUD_BRICK_STAIRS);
        return Map.copyOf(map);
    }

    private static Map<Material, Material> buildSupportMap() {
        final Map<Material, Material> map = new EnumMap<>(Material.class);
        map.put(Material.STONE, Material.STONE);
        map.put(Material.COBBLESTONE, Material.COBBLESTONE);
        map.put(Material.DEEPSLATE, Material.COBBLED_DEEPSLATE);
        map.put(Material.COBBLED_DEEPSLATE, Material.COBBLED_DEEPSLATE);
        map.put(Material.ANDESITE, Material.ANDESITE);
        map.put(Material.DIORITE, Material.DIORITE);
        map.put(Material.GRANITE, Material.GRANITE);
        map.put(Material.TUFF, Material.TUFF);
        map.put(Material.SANDSTONE, Material.SANDSTONE);
        map.put(Material.RED_SANDSTONE, Material.RED_SANDSTONE);
        map.put(Material.NETHERRACK, Material.BLACKSTONE);
        map.put(Material.BLACKSTONE, Material.BLACKSTONE);
        map.put(Material.NETHER_BRICKS, Material.NETHER_BRICKS);
        map.put(Material.END_STONE, Material.END_STONE_BRICKS);
        map.put(Material.END_STONE_BRICKS, Material.END_STONE_BRICKS);
        return Map.copyOf(map);
    }
}
