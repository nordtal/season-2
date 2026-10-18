package eu.nordtal.season.smp.config;

import eu.nordtal.season.spec.Specs;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The default track of the {@code milestones} group.
 * The aura of an objective is {@code round((budget ÷ objectives) × 5, to 10)}.
 * The spins of an objective are two for each player of the gate.
 */
final class DefaultTrack {

    private static final Budget NOTHING = new Budget(0, 0);

    static final List<MilestonesSpec.MilestoneEntry> LIST = List.of(
            milestone("waiting", "BORDER", 20, false, NOTHING, List.of()),
            milestone("departure", "BORDER", 43, true, NOTHING, List.of()),
            milestone(
                    "foothold",
                    "BORDER",
                    99,
                    false,
                    new Budget(30, 20),
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
            milestone(
                    "settlement",
                    "BORDER",
                    400,
                    false,
                    new Budget(60, 20),
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
            milestone(
                    "nether",
                    "NETHER",
                    0,
                    false,
                    new Budget(80, 16),
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
            milestone(
                    "end",
                    "END",
                    0,
                    false,
                    new Budget(80, 16),
                    List.of(
                            handIn("blaze-rods", "combat", 64, List.of("minecraft:blaze_rod")),
                            handIn("ender-pearls", "trade", 96, List.of("minecraft:ender_pearl")),
                            handIn("ancient-debris", "mining", 32, List.of("minecraft:ancient_debris")),
                            statistic(
                                    "endermen", "combat", 400, "minecraft:kill_entity", List.of("minecraft:enderman")),
                            advancement("blaze-rod", 8, "minecraft:nether/obtain_blaze_rod"))),
            milestone(
                    "expanse",
                    "BORDER",
                    900,
                    false,
                    new Budget(110, 12),
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
            milestone(
                    "frontier",
                    "BORDER",
                    4000,
                    false,
                    new Budget(170, 10),
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

    private record Budget(int aura, int spins) {}

    private static MilestonesSpec.MilestoneEntry milestone(
            final String key,
            final String unlocks,
            final int borderDiameter,
            final boolean adminUnlocked,
            final Budget budget,
            final List<Map<String, Object>> objectives) {
        final Map<String, Object> values = new LinkedHashMap<>();
        values.put("key", key);
        values.put("unlocks", unlocks);
        values.put("border-diameter", borderDiameter);
        values.put("admin-unlocked", adminUnlocked);
        values.put(
                "objectives",
                objectives.stream()
                        .map(objective -> {
                            final Map<String, Object> paid = new LinkedHashMap<>(objective);
                            paid.put("aura-budget", budget.aura());
                            paid.put("spin-budget", budget.spins());
                            return Specs.createUnsafe(MilestonesSpec.ObjectiveEntry.class, paid);
                        })
                        .toList());
        return Specs.createUnsafe(MilestonesSpec.MilestoneEntry.class, values);
    }

    private static Map<String, Object> handIn(
            final String key, final String role, final long target, final List<String> items) {
        return objective(key, "HAND_IN", role, target, items, "", List.of(), "");
    }

    private static Map<String, Object> statistic(
            final String key,
            final String role,
            final long target,
            final String statistic,
            final List<String> subjects) {
        return objective(key, "STATISTIC", role, target, List.of(), statistic, subjects, "");
    }

    private static Map<String, Object> advancement(final String key, final long players, final String advancement) {
        return objective(key, "ADVANCEMENT", "participation", players, List.of(), "", List.of(), advancement);
    }

    private static Map<String, Object> objective(
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
        return values;
    }
}
