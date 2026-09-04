package eu.nordtal.s2.smp.config;

import eu.nordtal.jcore.config.spec.Specs;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The lists a fresh {@code config.yml} is written with.
 *
 * <p>Same mechanism and same caveat as {@link DefaultTrack} and {@code discord-bot}'s
 * {@code DefaultTiers}: a default method on a spec interface can only return values, a nested spec
 * is served by a reflective proxy, and {@code Specs.createUnsafe} does <b>not</b> apply defaults -
 * every key of the spec has to appear in the map or it comes out null.
 *
 * <p>Everything here is a proposal. See {@link SmpSpec}'s comments for the reasoning behind each
 * list; this class is only the values.
 */
final class DefaultSmp {

    /**
     * Placeholder boxes. The spawn build does not exist yet, so these describe the shape of the
     * setting rather than the real spawn - one box per world with a balloon, generous enough to
     * cover a small built area, and meant to be replaced once there is something to measure.
     */
    static final List<SmpSpec.SpawnRegionSpec> SPAWN_REGIONS = List.of(
            region("nordtal", 106 - 32, 40, 88 - 32, 106 + 32, 140, 88 + 32),
            region("farm", -32, 40, -32, 32, 140, 32),
            region("nordtal_nether", -32, 20, -32, 32, 120, 32));

    /**
     * Placeholder balloon volumes, one per world that has one - Nordtal, the farm world and the
     * Nether. The End has none on purpose.
     *
     * <p>Nordtal's box sits at radius ~15 of the border centre X 106 / Z 88, which is the only one
     * of these numbers that is not arbitrary: it has to land between radius 10 and 21.5 so that
     * border 20 withholds travel and the opening expansion to 43 hands it over. The real box comes
     * from the built spawn; this one is shaped so the constraint is satisfied by the defaults and
     * a mistake in the real coordinates is what the enable-time check catches.
     */
    static final List<SmpSpec.BalloonSpec> BALLOONS = List.of(
            balloon("nordtal", 106 + 13, 64, 88 - 2, 106 + 17, 68, 88 + 2),
            balloon("farm", -2, 64, -2, 2, 68, 2),
            balloon("nordtal_nether", -2, 32, -2, 2, 36, 2));

    /**
     * Placeholder board anchors, both in Nordtal near the border centre. Replaced from the built
     * spawn; what matters about them is only that there are two and that they are inside radius 10,
     * with everything else social, so the opening minutes withhold travel and nothing else.
     */
    static final List<SmpSpec.BoardSpec> BOARDS = List.of(
            board("OBJECTIVE", "nordtal", 106 - 4, 68, 88 + 6, 0f),
            board("AURA", "nordtal", 106 + 4, 68, 88 + 6, 0f));

    /**
     * Placeholder duel platforms, both 3 x 3 and both inside radius 10 of the border centre.
     */
    static final List<SmpSpec.DuelPlatformSpec> DUEL_PLATFORMS = List.of(
            platform("SWORD", "nordtal", 106 - 7, 68, 88 - 1, 106 - 5, 69, 88 + 1),
            platform("BOW", "nordtal", 106 + 5, 68, 88 - 1, 106 + 7, 69, 88 + 1));

    /** A placeholder wheel, inside radius 10 with everything else social. */
    static final List<SmpSpec.SpawnRegionSpec> WHEEL_REGIONS = List.of(
            region("nordtal", 106 - 2, 68, 88 + 2, 106 - 1, 70, 88 + 3));

    /** A placeholder NPC, inside radius 10 with everything else social. */
    static final SmpSpec.NpcSpec NPC = npc("nordtal", 106.5, 68.0, 92.5, 180f, "", "Nordtal");

    /**
     * The curated advancement list. Twenty-two entries across the four bands described in
     * {@link SmpSpec#advancementAwards()}, chosen to follow the shape of a playthrough.
     */
    static final List<SmpSpec.AdvancementAwardSpec> ADVANCEMENT_AWARDS = List.of(
            // 2 - the first hours. Everybody gets these and they are worth noticing, not rewarding.
            award("minecraft:story/mine_stone", 2),
            award("minecraft:story/upgrade_tools", 2),
            award("minecraft:story/smelt_iron", 2),
            award("minecraft:story/iron_tools", 2),
            award("minecraft:husbandry/plant_seed", 2),
            award("minecraft:husbandry/breed_an_animal", 2),

            // 4 - a first real project: diamonds, a bed, a working farm.
            award("minecraft:story/mine_diamond", 4),
            award("minecraft:story/shiny_gear", 4),
            award("minecraft:story/enchant_item", 4),
            award("minecraft:husbandry/make_a_sign_glow", 4),
            award("minecraft:adventure/trade", 4),

            // 6 - a real trip. Every one of these needs the Nether, which is a milestone in itself.
            award("minecraft:nether/root", 6),
            award("minecraft:nether/obtain_blaze_rod", 6),
            award("minecraft:nether/find_fortress", 6),
            award("minecraft:nether/obtain_crying_obsidian", 6),
            award("minecraft:adventure/kill_a_mob", 6),

            // 8 - a project measured in evenings, and mostly a group one.
            award("minecraft:end/root", 8),
            award("minecraft:nether/obtain_ancient_debris", 8),
            award("minecraft:adventure/hero_of_the_village", 8),
            award("minecraft:end/find_end_city", 8),

            // 10 - the two that take a season, and the only two worth as much as a small objective.
            award("minecraft:end/kill_dragon", 10),
            award("minecraft:nether/netherite_armor", 10));

    /**
     * The winner's head start: the thing everybody wants and nobody has on day one, plus a head
     * start on gear that is spent the moment it is used.
     */
    static final List<SmpSpec.WheelPrizeSpec> HG_WINNER_ITEMS = List.of(
            item("ELYTRA", 1, 1),
            item("NETHERITE_INGOT", 1, 1));

    /**
     * The wheel's pool. Weights are relative; as written, the common band is about 70 % of spins,
     * the uncommon band about 26 %, and the rare band about one spin in twenty-five.
     */
    static final List<SmpSpec.WheelPrizeSpec> WHEEL_PRIZES = List.of(
            // common - useful, never decisive. Total weight 700.
            item("COOKED_BEEF", 32, 120),
            item("OAK_LOG", 64, 110),
            item("COAL", 32, 110),
            item("TORCH", 64, 100),
            item("STONE_BRICKS", 128, 90),
            item("OAK_SAPLING", 16, 60),
            item("BREAD", 32, 60),
            item("GLASS", 64, 50),

            // uncommon - pleasant, still ordinary. Total weight 260.
            item("IRON_INGOT", 32, 70),
            item("REDSTONE", 64, 50),
            item("LAPIS_LAZULI", 32, 40),
            item("ENCHANTED_BOOK", 1, 40),
            item("GOLDEN_APPLE", 4, 30),
            item("EXPERIENCE_BOTTLE", 16, 30),

            // rare - things you occasionally need and hate farming, chosen to encourage trade.
            // Total weight 40, so roughly one spin in twenty-five.
            item("ANCIENT_DEBRIS", 2, 12),
            item("SHULKER_SHELL", 2, 10),
            item("END_CRYSTAL", 2, 8),
            item("NETHER_STAR", 1, 4),
            item("ELYTRA", 1, 3),
            item("TOTEM_OF_UNDYING", 1, 3));

    /** Iron and no enchantments: aim and timing over about a minute. */
    static final List<SmpSpec.WheelPrizeSpec> DUEL_LOADOUT_SWORD = List.of(
            item("IRON_SWORD", 1, 1),
            item("SHIELD", 1, 1),
            item("IRON_HELMET", 1, 1),
            item("IRON_CHESTPLATE", 1, 1),
            item("IRON_LEGGINGS", 1, 1),
            item("IRON_BOOTS", 1, 1),
            item("COOKED_BEEF", 8, 1));

    /** Lighter armour than the sword loadout, so a hit matters and a miss costs. */
    static final List<SmpSpec.WheelPrizeSpec> DUEL_LOADOUT_BOW = List.of(
            item("BOW", 1, 1),
            item("ARROW", 64, 1),
            item("IRON_SWORD", 1, 1),
            item("LEATHER_HELMET", 1, 1),
            item("CHAINMAIL_CHESTPLATE", 1, 1),
            item("LEATHER_LEGGINGS", 1, 1),
            item("LEATHER_BOOTS", 1, 1),
            item("COOKED_BEEF", 8, 1));

    // ---------------------------------------------------------------- the sound vocabulary
    //

    private DefaultSmp() {
    }

    private static SmpSpec.SpawnRegionSpec region(final String world, final int minX, final int minY,
                                                  final int minZ, final int maxX, final int maxY,
                                                  final int maxZ) {
        final Map<String, Object> values = new LinkedHashMap<>();
        values.put("world", world);
        values.put("min-x", minX);
        values.put("min-y", minY);
        values.put("min-z", minZ);
        values.put("max-x", maxX);
        values.put("max-y", maxY);
        values.put("max-z", maxZ);
        return Specs.createUnsafe(SmpSpec.SpawnRegionSpec.class, values);
    }

    private static SmpSpec.BalloonSpec balloon(final String world, final int minX, final int minY,
                                               final int minZ, final int maxX, final int maxY,
                                               final int maxZ) {
        final Map<String, Object> values = new LinkedHashMap<>();
        values.put("world", world);
        values.put("min-x", minX);
        values.put("min-y", minY);
        values.put("min-z", minZ);
        values.put("max-x", maxX);
        values.put("max-y", maxY);
        values.put("max-z", maxZ);
        return Specs.createUnsafe(SmpSpec.BalloonSpec.class, values);
    }

    private static SmpSpec.BoardSpec board(final String kind, final String world, final double x,
                                           final double y, final double z, final float yaw) {
        final Map<String, Object> values = new LinkedHashMap<>();
        values.put("kind", kind);
        values.put("world", world);
        values.put("x", x);
        values.put("y", y);
        values.put("z", z);
        values.put("yaw", yaw);
        return Specs.createUnsafe(SmpSpec.BoardSpec.class, values);
    }

    private static SmpSpec.DuelPlatformSpec platform(final String type, final String world,
                                                     final int minX, final int minY, final int minZ,
                                                     final int maxX, final int maxY, final int maxZ) {
        final Map<String, Object> values = new LinkedHashMap<>();
        values.put("type", type);
        values.put("world", world);
        values.put("min-x", minX);
        values.put("min-y", minY);
        values.put("min-z", minZ);
        values.put("max-x", maxX);
        values.put("max-y", maxY);
        values.put("max-z", maxZ);
        return Specs.createUnsafe(SmpSpec.DuelPlatformSpec.class, values);
    }

    private static SmpSpec.NpcSpec npc(final String world, final double x, final double y,
                                       final double z, final float yaw, final String skinName,
                                       final String name) {
        final Map<String, Object> values = new LinkedHashMap<>();
        values.put("world", world);
        values.put("x", x);
        values.put("y", y);
        values.put("z", z);
        values.put("yaw", yaw);
        values.put("skin-name", skinName);
        values.put("name", name);
        return Specs.createUnsafe(SmpSpec.NpcSpec.class, values);
    }

    private static SmpSpec.AdvancementAwardSpec award(final String advancement, final int aura) {
        final Map<String, Object> values = new LinkedHashMap<>();
        values.put("advancement", advancement);
        values.put("aura", aura);
        return Specs.createUnsafe(SmpSpec.AdvancementAwardSpec.class, values);
    }

    private static SmpSpec.WheelPrizeSpec item(final String item, final int amount, final int weight) {
        final Map<String, Object> values = new LinkedHashMap<>();
        values.put("item", item);
        values.put("amount", amount);
        values.put("weight", weight);
        return Specs.createUnsafe(SmpSpec.WheelPrizeSpec.class, values);
    }
}
