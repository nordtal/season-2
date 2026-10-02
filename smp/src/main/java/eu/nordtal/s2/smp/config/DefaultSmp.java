package eu.nordtal.s2.smp.config;

import eu.nordtal.jcore.config.spec.Specs;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The lists the {@code config} group defaults to; placeholders until the spawn is built.
 *
 * {@code Specs.createUnsafe} applies no defaults, so every key of a spec has to appear in its map.
 */
final class DefaultSmp {

    /** One box per world with a balloon. */
    static final List<SpawnRegionSpec> SPAWN_REGIONS = List.of(
            region("nordtal", 106 - 32, 40, 88 - 32, 106 + 32, 140, 88 + 32),
            region("nordtal_nether", -32, 20, -32, 32, 120, 32));

    /**
     * One balloon per world that has one; the End has none.
     *
     * Nordtal's box sits at radius ~15 of the border centre, between the 10 and 21.5 that {@code start()} checks.
     */
    static final List<BalloonSpec> BALLOONS = List.of(
            balloon("nordtal", 106 + 13, 64, 88 - 2, 106 + 17, 68, 88 + 2),
            balloon("nordtal_nether", -2, 32, -2, 2, 36, 2));

    /** Both in Nordtal, inside radius 10 of the border centre. */
    static final List<BoardSpec> BOARDS = List.of(
            board("OBJECTIVE", "nordtal", 106 - 4, 68, 88 + 6, 0f), board("AURA", "nordtal", 106 + 4, 68, 88 + 6, 0f));

    /** Both 3 x 3 and inside radius 10 of the border centre. */
    static final List<DuelPlatformSpec> DUEL_PLATFORMS = List.of(
            platform("SWORD", "nordtal", 106 - 7, 68, 88 - 1, 106 - 5, 69, 88 + 1),
            platform("BOW", "nordtal", 106 + 5, 68, 88 - 1, 106 + 7, 69, 88 + 1));

    /** Inside radius 10 with everything else social. */
    static final List<SpawnRegionSpec> WHEEL_REGIONS =
            List.of(region("nordtal", 106 - 2, 68, 88 + 2, 106 - 1, 70, 88 + 3));

    /** Inside radius 10 with everything else social. */
    static final NpcSpec NPC = npc("nordtal", 106.5, 68.0, 92.5, 180f, "", "Nordtal");

    /**
     * Landing points around the border centres; the Nether's Y is 32, since 64 there is as likely rock as air.
     *
     * {@code LandingSite#findSafeAt} is what keeps a wrong one from killing anybody.
     */
    static final SpawnPointSpec BALLOON_SPAWN_POINT_NORDTAL = spawnPoint(106.5, 68.0, 88.5, 0f, 0f);

    static final SpawnPointSpec BALLOON_SPAWN_POINT_NETHER = spawnPoint(0.5, 32.0, 0.5, 0f, 0f);

    static final SpawnPointSpec BALLOON_SPAWN_POINT_END = spawnPoint(0.5, 64.0, 0.5, 0f, 0f);

    /** The three of them together, which is what the top-level key answers with. */
    static final BalloonSpawnPointsSpec BALLOON_SPAWN_POINTS =
            balloonSpawnPoints(BALLOON_SPAWN_POINT_NORDTAL, BALLOON_SPAWN_POINT_NETHER, BALLOON_SPAWN_POINT_END);

    /** On Nordtal's border centre; the world name is written out, since a spec default cannot read another key. */
    static final FirstJoinSpawnSpec FIRST_JOIN_SPAWN = firstJoinSpawn("nordtal", 106.5, 68.0, 88.5, 0f, 0f);

    /** Twenty-two advancements across the four bands described in {@link SmpSpec#advancementAwards()}. */
    static final List<AdvancementAwardSpec> ADVANCEMENT_AWARDS = List.of(
            // 2: the first hours, worth noticing rather than rewarding.
            award("minecraft:story/mine_stone", 2),
            award("minecraft:story/upgrade_tools", 2),
            award("minecraft:story/smelt_iron", 2),
            award("minecraft:story/iron_tools", 2),
            award("minecraft:husbandry/plant_seed", 2),
            award("minecraft:husbandry/breed_an_animal", 2),

            // 4: a first real project, such as diamonds, a bed or a working farm.
            award("minecraft:story/mine_diamond", 4),
            award("minecraft:story/shiny_gear", 4),
            award("minecraft:story/enchant_item", 4),
            award("minecraft:husbandry/make_a_sign_glow", 4),
            award("minecraft:adventure/trade", 4),

            // 6: a real trip, each needing the Nether.
            award("minecraft:nether/root", 6),
            award("minecraft:nether/obtain_blaze_rod", 6),
            award("minecraft:nether/find_fortress", 6),
            award("minecraft:nether/obtain_crying_obsidian", 6),
            award("minecraft:adventure/kill_a_mob", 6),

            // 8: a project measured in evenings, mostly a group one.
            award("minecraft:end/root", 8),
            award("minecraft:nether/obtain_ancient_debris", 8),
            award("minecraft:adventure/hero_of_the_village", 8),
            award("minecraft:end/find_end_city", 8),

            // 10: the two that take a season.
            award("minecraft:end/kill_dragon", 10),
            award("minecraft:nether/netherite_armor", 10));

    /** The wheel's pool; the common band is about 70 % of spins, uncommon 26 %, rare one in twenty-five. */
    static final List<WheelPrizeSpec> WHEEL_PRIZES = List.of(
            // Common: useful, never decisive. Total weight 700.
            item("COOKED_BEEF", 32, 120),
            item("OAK_LOG", 64, 110),
            item("COAL", 32, 110),
            item("TORCH", 64, 100),
            item("STONE_BRICKS", 128, 90),
            item("OAK_SAPLING", 16, 60),
            item("BREAD", 32, 60),
            item("GLASS", 64, 50),

            // Uncommon: pleasant, still ordinary. Total weight 260.
            item("IRON_INGOT", 32, 70),
            item("REDSTONE", 64, 50),
            item("LAPIS_LAZULI", 32, 40),
            item("ENCHANTED_BOOK", 1, 40),
            item("GOLDEN_APPLE", 4, 30),
            item("EXPERIENCE_BOTTLE", 16, 30),

            // Rare: occasionally needed, hated to farm. Total weight 40.
            item("ANCIENT_DEBRIS", 2, 12),
            item("SHULKER_SHELL", 2, 10),
            item("END_CRYSTAL", 2, 8),
            item("NETHER_STAR", 1, 4),
            item("ELYTRA", 1, 3),
            item("TOTEM_OF_UNDYING", 1, 3));

    /** Iron and no enchantments: aim and timing over about a minute. */
    static final List<WheelPrizeSpec> DUEL_LOADOUT_SWORD = List.of(
            item("IRON_SWORD", 1, 1),
            item("SHIELD", 1, 1),
            item("IRON_HELMET", 1, 1),
            item("IRON_CHESTPLATE", 1, 1),
            item("IRON_LEGGINGS", 1, 1),
            item("IRON_BOOTS", 1, 1),
            item("COOKED_BEEF", 8, 1));

    /** Lighter armour than the sword loadout, so a hit matters and a miss costs. */
    static final List<WheelPrizeSpec> DUEL_LOADOUT_BOW = List.of(
            item("BOW", 1, 1),
            item("ARROW", 64, 1),
            item("IRON_SWORD", 1, 1),
            item("LEATHER_HELMET", 1, 1),
            item("CHAINMAIL_CHESTPLATE", 1, 1),
            item("LEATHER_LEGGINGS", 1, 1),
            item("LEATHER_BOOTS", 1, 1),
            item("COOKED_BEEF", 8, 1));

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

    private static BalloonSpec balloon(
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
        return Specs.createUnsafe(BalloonSpec.class, values);
    }

    /** One default board; every key the spec declares has to appear in this map. */
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

    private static SpawnPointSpec spawnPoint(
            final double x, final double y, final double z, final float yaw, final float pitch) {
        final Map<String, Object> values = new LinkedHashMap<>();
        values.put("x", x);
        values.put("y", y);
        values.put("z", z);
        values.put("yaw", yaw);
        values.put("pitch", pitch);
        return Specs.createUnsafe(SpawnPointSpec.class, values);
    }

    private static BalloonSpawnPointsSpec balloonSpawnPoints(
            final SpawnPointSpec nordtal, final SpawnPointSpec nether, final SpawnPointSpec end) {
        final Map<String, Object> values = new LinkedHashMap<>();
        values.put("nordtal", nordtal);
        values.put("nether", nether);
        values.put("end", end);
        return Specs.createUnsafe(BalloonSpawnPointsSpec.class, values);
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
