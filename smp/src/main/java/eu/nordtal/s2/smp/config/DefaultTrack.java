package eu.nordtal.s2.smp.config;

import eu.nordtal.jcore.config.spec.Specs;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The track the {@code milestones} group defaults to; every key of a spec has to appear in its map.
 *
 * The shape is the decision; the items are examples, and each pot is {@code round((budget ÷ objectives) × 5, to 10)}.
 */
final class DefaultTrack {

    /** The eight milestones, in track order. */
    static final List<MilestonesSpec.MilestoneEntry> LIST = List.of(

            // M0: where the phase switch leaves the world. Border 20 is a physical gate.
            milestone("waiting", "BORDER", 20, 0, false, List.of()),

            // M1: opened by an admin at the opening.
            milestone("departure", "BORDER", 43, 0, true, List.of()),

            // M2 foothold: 4 objectives, 20 h, pot 30 each, gate 10 players, day 1.
            milestone(
                    "foothold",
                    "BORDER",
                    99,
                    30,
                    false,
                    List.of(
                            handIn(
                                    "logs",
                                    "gathering",
                                    2048,
                                    List.of(
                                            "minecraft:oak_log",
                                            "minecraft:spruce_log",
                                            "minecraft:birch_log",
                                            "minecraft:jungle_log",
                                            "minecraft:acacia_log",
                                            "minecraft:dark_oak_log",
                                            "minecraft:mangrove_log",
                                            "minecraft:cherry_log",
                                            "minecraft:pale_oak_log")),
                            statistic(
                                    "coal",
                                    "mining",
                                    1500,
                                    "minecraft:mine_block",
                                    List.of("minecraft:coal_ore", "minecraft:deepslate_coal_ore")),
                            statistic("zombies", "combat", 500, "minecraft:kill_entity", List.of("minecraft:zombie")),
                            advancement("iron-tools", 10, "minecraft:story/iron_tools"))),

            // M3 settlement: 4 objectives, 45 h, pot 60 each, gate 10 players, days 1 to 2.
            milestone(
                    "settlement",
                    "BORDER",
                    400,
                    60,
                    false,
                    List.of(
                            handIn("iron", "production", 512, List.of("minecraft:iron_ingot")),
                            handIn("diamonds", "mining", 64, List.of("minecraft:diamond")),
                            statistic(
                                    "hostiles",
                                    "combat",
                                    2000,
                                    "minecraft:kill_entity",
                                    List.of(
                                            "minecraft:zombie",
                                            "minecraft:skeleton",
                                            "minecraft:spider",
                                            "minecraft:creeper",
                                            "minecraft:enderman",
                                            "minecraft:witch",
                                            "minecraft:drowned",
                                            "minecraft:husk",
                                            "minecraft:stray",
                                            "minecraft:cave_spider",
                                            "minecraft:pillager",
                                            "minecraft:slime",
                                            "minecraft:phantom",
                                            "minecraft:zombie_villager",
                                            "minecraft:bogged",
                                            "minecraft:breeze")),
                            advancement("mine-diamond", 10, "minecraft:story/mine_diamond"))),

            // M4 nether: 4 objectives, 60 h, pot 80 each, gate 8 players, days 2 to 3.
            milestone(
                    "nether",
                    "NETHER",
                    0,
                    80,
                    false,
                    List.of(
                            handIn("obsidian", "mining", 64, List.of("minecraft:obsidian")),
                            handIn("stone-bricks", "crafting", 1024, List.of("minecraft:stone_bricks")),
                            statistic(
                                    "gold",
                                    "mining",
                                    512,
                                    "minecraft:mine_block",
                                    List.of("minecraft:gold_ore", "minecraft:deepslate_gold_ore")),
                            advancement("form-obsidian", 8, "minecraft:story/form_obsidian"))),

            // M5 end: 5 objectives, 75 h, pot 80 each, gate 8 players, days 3 to 4.
            milestone(
                    "end",
                    "END",
                    0,
                    80,
                    false,
                    List.of(
                            handIn("blaze-rods", "combat", 64, List.of("minecraft:blaze_rod")),
                            handIn("ender-pearls", "trade", 96, List.of("minecraft:ender_pearl")),
                            handIn("ancient-debris", "mining", 32, List.of("minecraft:ancient_debris")),
                            statistic(
                                    "endermen", "combat", 400, "minecraft:kill_entity", List.of("minecraft:enderman")),
                            advancement("blaze-rod", 8, "minecraft:nether/obtain_blaze_rod"))),

            // M6 expanse: 5 objectives, 110 h, pot 110 each, gate 6 players, ~5 days.
            milestone(
                    "expanse",
                    "BORDER",
                    900,
                    110,
                    false,
                    List.of(
                            handIn("iron", "production", 4096, List.of("minecraft:iron_ingot")),
                            handIn(
                                    "building-blocks",
                                    "mining",
                                    16384,
                                    List.of(
                                            "minecraft:stone",
                                            "minecraft:cobblestone",
                                            "minecraft:deepslate",
                                            "minecraft:cobbled_deepslate",
                                            "minecraft:andesite",
                                            "minecraft:diorite",
                                            "minecraft:granite",
                                            "minecraft:tuff",
                                            "minecraft:sandstone",
                                            "minecraft:netherrack")),
                            handIn("diamonds", "mining", 128, List.of("minecraft:diamond")),
                            statistic(
                                    "raiders",
                                    "combat",
                                    1000,
                                    "minecraft:kill_entity",
                                    List.of(
                                            "minecraft:pillager",
                                            "minecraft:vindicator",
                                            "minecraft:evoker",
                                            "minecraft:ravager",
                                            "minecraft:witch",
                                            "minecraft:illusioner")),
                            advancement("hero-of-the-village", 6, "minecraft:adventure/hero_of_the_village"))),

            // M7 frontier: 5 objectives, 170 h, pot 170 each, gate 5 players, ~2 weeks.
            milestone(
                    "frontier",
                    "BORDER",
                    4000,
                    170,
                    false,
                    List.of(
                            handIn("iron", "production", 8192, List.of("minecraft:iron_ingot")),
                            handIn("netherite-scrap", "mining", 128, List.of("minecraft:netherite_scrap")),
                            handIn(
                                    "building-blocks",
                                    "production",
                                    32768,
                                    List.of(
                                            "minecraft:stone",
                                            "minecraft:cobblestone",
                                            "minecraft:deepslate",
                                            "minecraft:cobbled_deepslate",
                                            "minecraft:andesite",
                                            "minecraft:diorite",
                                            "minecraft:granite",
                                            "minecraft:tuff",
                                            "minecraft:sandstone",
                                            "minecraft:netherrack")),
                            handIn("shulker-shells", "exploration", 16, List.of("minecraft:shulker_shell")),
                            advancement("netherite-armor", 5, "minecraft:nether/netherite_armor"))));

    private DefaultTrack() {}

    private static MilestonesSpec.MilestoneEntry milestone(
            final String key,
            final String unlocks,
            final int borderDiameter,
            final int objectivePot,
            final boolean adminUnlocked,
            final List<MilestonesSpec.ObjectiveEntry> objectives) {
        final Map<String, Object> values = new LinkedHashMap<>();
        values.put("key", key);
        values.put("unlocks", unlocks);
        values.put("border-diameter", borderDiameter);
        values.put("objective-pot", objectivePot);
        values.put("admin-unlocked", adminUnlocked);
        values.put("objectives", objectives);
        return Specs.createUnsafe(MilestonesSpec.MilestoneEntry.class, values);
    }

    private static MilestonesSpec.ObjectiveEntry handIn(
            final String key, final String role, final long target, final List<String> items) {
        return objective(key, "HAND_IN", role, target, items, "", List.of(), "");
    }

    private static MilestonesSpec.ObjectiveEntry statistic(
            final String key,
            final String role,
            final long target,
            final String statistic,
            final List<String> subjects) {
        return objective(key, "STATISTIC", role, target, List.of(), statistic, subjects, "");
    }

    private static MilestonesSpec.ObjectiveEntry advancement(
            final String key, final long players, final String advancement) {
        return objective(key, "ADVANCEMENT", "participation", players, List.of(), "", List.of(), advancement);
    }

    private static MilestonesSpec.ObjectiveEntry objective(
            final String key,
            final String type,
            final String role,
            final long target,
            final List<String> items,
            final String statistic,
            final List<String> subjects,
            final String advancement) {
        final Map<String, Object> values = new LinkedHashMap<>();
        values.put("key", key);
        values.put("type", type);
        values.put("role", role);
        values.put("target", target);
        values.put("items", items);
        values.put("statistic", statistic);
        values.put("subjects", subjects);
        values.put("advancement", advancement);
        return Specs.createUnsafe(MilestonesSpec.ObjectiveEntry.class, values);
    }
}
