package eu.nordtal.season.smp.config;

import eu.nordtal.season.spec.Specs;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The default lists of the {@code config} group. Every spec built here sets every key. */
final class DefaultSmp {

    static final List<SpawnRegionSpec> SPAWN_REGIONS = List.of(
            region("nordtal", 106 - 32, 40, 88 - 32, 106 + 32, 140, 88 + 32),
            region("nordtal_nether", -32, 20, -32, 32, 120, 32));

    static final List<BoardSpec> BOARDS = List.of(
            board("OBJECTIVE", "nordtal", 106 - 4, 68, 88 + 6, 0f), board("AURA", "nordtal", 106 + 4, 68, 88 + 6, 0f));

    static final List<DuelPlatformSpec> DUEL_PLATFORMS = List.of(
            platform("SWORD", "nordtal", 106 - 7, 68, 88 - 1, 106 - 5, 69, 88 + 1),
            platform("BOW", "nordtal", 106 + 5, 68, 88 - 1, 106 + 7, 69, 88 + 1));

    static final List<SpawnRegionSpec> WHEEL_REGIONS =
            List.of(region("nordtal", 106 - 2, 68, 88 + 2, 106 - 1, 70, 88 + 3));

    static final NpcSpec NPC = npc("nordtal", 106.5, 68.0, 92.5, 180f, "", "Nordtal");

    static final FirstJoinSpawnSpec FIRST_JOIN_SPAWN = firstJoinSpawn("nordtal", 106.5, 68.0, 88.5, 0f, 0f);
    static final List<AdvancementAwardSpec> ADVANCEMENT_AWARDS = List.of(
            award("minecraft:story/mine_stone", 2),
            award("minecraft:story/upgrade_tools", 2),
            award("minecraft:story/smelt_iron", 2),
            award("minecraft:story/iron_tools", 2),
            award("minecraft:husbandry/plant_seed", 2),
            award("minecraft:husbandry/breed_an_animal", 2),
            award("minecraft:story/mine_diamond", 4),
            award("minecraft:story/shiny_gear", 4),
            award("minecraft:story/enchant_item", 4),
            award("minecraft:husbandry/make_a_sign_glow", 4),
            award("minecraft:adventure/trade", 4),
            award("minecraft:nether/root", 6),
            award("minecraft:nether/obtain_blaze_rod", 6),
            award("minecraft:nether/find_fortress", 6),
            award("minecraft:nether/obtain_crying_obsidian", 6),
            award("minecraft:adventure/kill_a_mob", 6),
            award("minecraft:end/root", 8),
            award("minecraft:nether/obtain_ancient_debris", 8),
            award("minecraft:adventure/hero_of_the_village", 8),
            award("minecraft:end/find_end_city", 8),
            award("minecraft:end/kill_dragon", 10),
            award("minecraft:nether/netherite_armor", 10));

    static final List<WheelPrizeSpec> WHEEL_PRIZES = List.of(
            item("minecraft:cooked_beef", 32, 120),
            item("minecraft:oak_log", 64, 110),
            item("minecraft:coal", 32, 110),
            item("minecraft:torch", 64, 100),
            item("minecraft:stone_bricks", 128, 90),
            item("minecraft:oak_sapling", 16, 60),
            item("minecraft:bread", 32, 60),
            item("minecraft:glass", 64, 50),
            item("minecraft:iron_ingot", 32, 70),
            item("minecraft:redstone", 64, 50),
            item("minecraft:lapis_lazuli", 32, 40),
            item("minecraft:enchanted_book", 1, 40),
            item("minecraft:golden_apple", 4, 30),
            item("minecraft:experience_bottle", 16, 30),
            item("minecraft:ancient_debris", 2, 12),
            item("minecraft:shulker_shell", 2, 10),
            item("minecraft:end_crystal", 2, 8),
            item("minecraft:nether_star", 1, 4),
            item("minecraft:elytra", 1, 3),
            item("minecraft:totem_of_undying", 1, 3));

    static final List<WheelPrizeSpec> DUEL_LOADOUT_SWORD = List.of(
            item("minecraft:iron_sword", 1, 1),
            item("minecraft:shield", 1, 1),
            item("minecraft:iron_helmet", 1, 1),
            item("minecraft:iron_chestplate", 1, 1),
            item("minecraft:iron_leggings", 1, 1),
            item("minecraft:iron_boots", 1, 1),
            item("minecraft:cooked_beef", 8, 1));

    static final List<WheelPrizeSpec> DUEL_LOADOUT_BOW = List.of(
            item("BOW", 1, 1),
            item("minecraft:arrow", 64, 1),
            item("minecraft:iron_sword", 1, 1),
            item("minecraft:leather_helmet", 1, 1),
            item("minecraft:chainmail_chestplate", 1, 1),
            item("minecraft:leather_leggings", 1, 1),
            item("minecraft:leather_boots", 1, 1),
            item("minecraft:cooked_beef", 8, 1));

    private DefaultSmp() {}

    private static SpawnRegionSpec region(
            final String world,
            final int minX,
            final int minY,
            final int minZ,
            final int maxX,
            final int maxY,
            final int maxZ) {
        final Map<String, Object> values = new LinkedHashMap<>();
        values.put("world", world);
        values.put("min-x", minX);
        values.put("min-y", minY);
        values.put("min-z", minZ);
        values.put("max-x", maxX);
        values.put("max-y", maxY);
        values.put("max-z", maxZ);
        return Specs.createUnsafe(SpawnRegionSpec.class, values);
    }

    private static BoardSpec board(
            final String kind, final String world, final double x, final double y, final double z, final float yaw) {
        final Map<String, Object> values = new LinkedHashMap<>();
        values.put("kind", kind);
        values.put("world", world);
        values.put("x", x);
        values.put("y", y);
        values.put("z", z);
        values.put("yaw", yaw);
        values.put("width", 180);
        return Specs.createUnsafe(BoardSpec.class, values);
    }

    private static DuelPlatformSpec platform(
            final String type,
            final String world,
            final int minX,
            final int minY,
            final int minZ,
            final int maxX,
            final int maxY,
            final int maxZ) {
        final Map<String, Object> values = new LinkedHashMap<>();
        values.put("type", type);
        values.put("world", world);
        values.put("min-x", minX);
        values.put("min-y", minY);
        values.put("min-z", minZ);
        values.put("max-x", maxX);
        values.put("max-y", maxY);
        values.put("max-z", maxZ);
        return Specs.createUnsafe(DuelPlatformSpec.class, values);
    }

    private static NpcSpec npc(
            final String world,
            final double x,
            final double y,
            final double z,
            final float yaw,
            final String skinName,
            final String name) {
        final Map<String, Object> values = new LinkedHashMap<>();
        values.put("world", world);
        values.put("x", x);
        values.put("y", y);
        values.put("z", z);
        values.put("yaw", yaw);
        values.put("skin-name", skinName);
        values.put("name", name);
        return Specs.createUnsafe(NpcSpec.class, values);
    }

    private static FirstJoinSpawnSpec firstJoinSpawn(
            final String world, final double x, final double y, final double z, final float yaw, final float pitch) {
        final Map<String, Object> values = new LinkedHashMap<>();
        values.put("world", world);
        values.put("x", x);
        values.put("y", y);
        values.put("z", z);
        values.put("yaw", yaw);
        values.put("pitch", pitch);
        return Specs.createUnsafe(FirstJoinSpawnSpec.class, values);
    }

    private static AdvancementAwardSpec award(final String advancement, final int aura) {
        final Map<String, Object> values = new LinkedHashMap<>();
        values.put("advancement", advancement);
        values.put("aura", aura);
        return Specs.createUnsafe(AdvancementAwardSpec.class, values);
    }

    private static WheelPrizeSpec item(final String item, final int amount, final int weight) {
        final Map<String, Object> values = new LinkedHashMap<>();
        values.put("item", item);
        values.put("amount", amount);
        values.put("weight", weight);
        return Specs.createUnsafe(WheelPrizeSpec.class, values);
    }
}
