package eu.nordtal.s2.smp.config;

import eu.nordtal.jcore.config.spec.Specs;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The track a fresh {@code milestones.yml} is written with: docs/smp.md#the-track and
 * docs/smp.md#the-objectives, entry for entry.
 *
 * <h2>Why this class exists</h2>
 * The same reason {@code discord-bot}'s {@code DefaultTiers} does. A default method on a spec
 * interface can only return values, and a nested spec is served by a reflective proxy - there is no
 * {@code new MilestoneEntry(...)} to write. {@code Specs.createUnsafe} is jcore's documented way to
 * build one from a map of its keys, and jcore's writer knows how to serialise a list of them.
 * <p>
 * <b>{@code createUnsafe} does not apply defaults</b>, so <em>every</em> key of the spec has to be
 * listed in the map. A new setting on {@link MilestonesSpec.ObjectiveEntry} that is not added to
 * {@link #objective} comes out null.
 * </p>
 * <p>
 * Two levels of nesting - a list of milestones each holding a list of objectives - is one level
 * more than anything in this repository had used before, and it is <b>verified rather than
 * assumed</b>: {@code MilestonesTest} writes a fresh file, reads it back, and asserts the whole
 * track survives the round trip.
 * </p>
 *
 * <h2>Reading the numbers below</h2>
 * The <em>shape</em> is the decision: how many objectives a milestone has, which type each is,
 * which role it serves and which share of the budget it carries. The <em>items and advancements</em>
 * are one worked example each and are expected to be corrected. The pots are arithmetic:
 * {@code round((budget ÷ objectives) × 5, to 10)} against a budget in community play hours.
 */
final class DefaultTrack {

    /** The eight milestones of docs/smp.md#the-track, in order. */
    static final List<MilestonesSpec.MilestoneEntry> LIST = List.of(

            // M0. Where the phase switch leaves the world. Border 20 is a physical gate rather than
            // ceremony: the balloon stands outside radius 10, so 20 withholds the farm world.
            milestone("waiting", "BORDER", 20, 0, false, List.of()),

            // M1. Opened by an admin at the opening, and the whole content of the season's first
            // minutes - 43 is what puts the balloon inside the border and hands over travel.
            milestone("departure", "BORDER", 43, 0, true, List.of()),

            // M2 foothold - 4 objectives, 20 h, pot 30 each, gate 10 players. Expected: day 1.
            // Nothing farmable yet: farms can only be built in Nordtal and border 99 has no room.
            milestone("foothold", "BORDER", 99, 30, false, List.of(
                    handIn("logs", "gathering", 2048, List.of(
                            "OAK_LOG", "SPRUCE_LOG", "BIRCH_LOG", "JUNGLE_LOG", "ACACIA_LOG",
                            "DARK_OAK_LOG", "MANGROVE_LOG", "CHERRY_LOG", "PALE_OAK_LOG")),
                    statistic("coal", "mining", 1500, "MINE_BLOCK", List.of("COAL_ORE", "DEEPSLATE_COAL_ORE")),
                    statistic("zombies", "combat", 500, "KILL_ENTITY", List.of("ZOMBIE")),
                    advancement("iron-tools", 10, "minecraft:story/iron_tools"))),

            // M3 settlement - 4 objectives, 45 h, pot 60 each, gate 10 players. Expected: day 1-2.
            // The first farmable hand-in appears here, and deliberately.
            milestone("settlement", "BORDER", 400, 60, false, List.of(
                    handIn("iron", "production", 512, List.of("IRON_INGOT")),
                    handIn("diamonds", "mining", 64, List.of("DIAMOND")),
                    statistic("hostiles", "combat", 2000, "KILL_ENTITY", List.of(
                            "ZOMBIE", "SKELETON", "SPIDER", "CREEPER", "ENDERMAN", "WITCH",
                            "DROWNED", "HUSK", "STRAY", "CAVE_SPIDER", "PILLAGER", "SLIME",
                            "PHANTOM", "ZOMBIE_VILLAGER", "BOGGED", "BREEZE")),
                    advancement("mine-diamond", 10, "minecraft:story/mine_diamond"))),

            // M4 nether - 4 objectives, 60 h, pot 80 each, gate 8 players. Expected: day 2-3.
            // The dimension IS the reward, so there is no border step attached to it.
            milestone("nether", "NETHER", 0, 80, false, List.of(
                    handIn("obsidian", "mining", 64, List.of("OBSIDIAN")),
                    handIn("stone-bricks", "crafting", 1024, List.of("STONE_BRICKS")),
                    statistic("gold", "mining", 512, "MINE_BLOCK", List.of("GOLD_ORE", "DEEPSLATE_GOLD_ORE")),
                    advancement("form-obsidian", 8, "minecraft:story/form_obsidian"))),

            // M5 end - 5 objectives, 75 h, pot 80 each, gate 8 players. Expected: day 3-4.
            // 200 of the track's 480 hours sit at or before here, which is what puts the End on
            // day three at 20 players x 2.5 h a day.
            milestone("end", "END", 0, 80, false, List.of(
                    handIn("blaze-rods", "combat", 64, List.of("BLAZE_ROD")),
                    handIn("ender-pearls", "trade", 96, List.of("ENDER_PEARL")),
                    handIn("ancient-debris", "mining", 32, List.of("ANCIENT_DEBRIS")),
                    statistic("endermen", "combat", 400, "KILL_ENTITY", List.of("ENDERMAN")),
                    advancement("blaze-rod", 8, "minecraft:nether/obtain_blaze_rod"))),

            // M6 expanse - 5 objectives, 110 h, pot 110 each, gate 6 players. Expected: ~5 days.
            // The quantities here are where a farm becomes clearly worth building.
            milestone("expanse", "BORDER", 900, 110, false, List.of(
                    handIn("iron", "production", 4096, List.of("IRON_INGOT")),
                    handIn("building-blocks", "mining", 16384, List.of(
                            "STONE", "COBBLESTONE", "DEEPSLATE", "COBBLED_DEEPSLATE", "ANDESITE",
                            "DIORITE", "GRANITE", "TUFF", "SANDSTONE", "NETHERRACK")),
                    handIn("diamonds", "mining", 128, List.of("DIAMOND")),
                    statistic("raiders", "combat", 1000, "KILL_ENTITY", List.of(
                            "PILLAGER", "VINDICATOR", "EVOKER", "RAVAGER", "WITCH", "ILLUSIONER")),
                    advancement("hero-of-the-village", 6, "minecraft:adventure/hero_of_the_village"))),

            // M7 frontier - 5 objectives, 170 h, pot 170 each, gate 5 players. Expected: ~2 weeks.
            // Sized as 8 active players x 14 days x 1.5 h, against whoever is still there in week
            // three rather than against the launch crowd. These quantities cannot be met by hand:
            // building the farm is meant to BE the content of the second week.
            milestone("frontier", "BORDER", 4000, 170, false, List.of(
                    handIn("iron", "production", 8192, List.of("IRON_INGOT")),
                    handIn("netherite-scrap", "mining", 128, List.of("NETHERITE_SCRAP")),
                    handIn("building-blocks", "production", 32768, List.of(
                            "STONE", "COBBLESTONE", "DEEPSLATE", "COBBLED_DEEPSLATE", "ANDESITE",
                            "DIORITE", "GRANITE", "TUFF", "SANDSTONE", "NETHERRACK")),
                    handIn("shulker-shells", "exploration", 16, List.of("SHULKER_SHELL")),
                    advancement("netherite-armor", 5, "minecraft:nether/netherite_armor"))));

    private DefaultTrack() {
    }

    private static MilestonesSpec.MilestoneEntry milestone(
            final String key, final String unlocks, final int borderDiameter, final int objectivePot,
            final boolean adminUnlocked, final List<MilestonesSpec.ObjectiveEntry> objectives) {
        final Map<String, Object> values = new LinkedHashMap<>();
        values.put("key", key);
        values.put("unlocks", unlocks);
        values.put("border-diameter", borderDiameter);
        values.put("objective-pot", objectivePot);
        values.put("admin-unlocked", adminUnlocked);
        values.put("objectives", objectives);
        return Specs.createUnsafe(MilestonesSpec.MilestoneEntry.class, values);
    }

    private static MilestonesSpec.ObjectiveEntry handIn(final String key, final String role,
                                                        final long target, final List<String> items) {
        return objective(key, "HAND_IN", role, target, items, "", List.of(), "");
    }

    private static MilestonesSpec.ObjectiveEntry statistic(final String key, final String role,
                                                           final long target, final String statistic,
                                                           final List<String> subjects) {
        return objective(key, "STATISTIC", role, target, List.of(), statistic, subjects, "");
    }

    private static MilestonesSpec.ObjectiveEntry advancement(final String key, final long players,
                                                             final String advancement) {
        // Always the participation gate, so its role is never anything else and is not a parameter.
        return objective(key, "ADVANCEMENT", "participation", players, List.of(), "", List.of(), advancement);
    }

    private static MilestonesSpec.ObjectiveEntry objective(
            final String key, final String type, final String role, final long target,
            final List<String> items, final String statistic, final List<String> subjects,
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
