package eu.nordtal.season.hungergames.config;

import eu.nordtal.season.spec.Specs;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The four refill tiers the {@code config} group defaults to, basic to overpowered.
 *
 * Each is one shared chest's contents, one of each material, with no quantities or randomness.
 */
final class DefaultRefillTiers {

    static final List<HungerGamesSpec.RefillTierSpec> LIST = List.of(
            // 0h00: basic farming gear; the shield keeps an early fight from being a free kill.
            tier(
                    0,
                    List.of(
                            "minecraft:wooden_axe",
                            "minecraft:stone_sword",
                            "minecraft:shield",
                            "minecraft:bread",
                            "minecraft:apple",
                            "minecraft:wheat_seeds")),
            // 1h00: iron PvP gear.
            tier(
                    60,
                    List.of(
                            "minecraft:iron_sword",
                            "minecraft:iron_helmet",
                            "minecraft:iron_chestplate",
                            "minecraft:iron_leggings",
                            "minecraft:iron_boots",
                            "BOW",
                            "minecraft:arrow",
                            "minecraft:cooked_beef",
                            "minecraft:golden_carrot")),
            // 2h00: diamond gear.
            tier(
                    120,
                    List.of(
                            "minecraft:diamond_sword",
                            "minecraft:diamond_chestplate",
                            "minecraft:crossbow",
                            "minecraft:spectral_arrow",
                            "minecraft:golden_apple",
                            "minecraft:shield",
                            "minecraft:ender_pearl")),
            // 2h30: overpowered, so the last stretch rewards moving between points rather than camping one chest.
            tier(
                    150,
                    List.of(
                            "minecraft:netherite_sword",
                            "minecraft:netherite_chestplate",
                            "minecraft:enchanted_golden_apple",
                            "minecraft:totem_of_undying",
                            "minecraft:splash_potion")));

    private DefaultRefillTiers() {}

    private static HungerGamesSpec.RefillTierSpec tier(final int delayMinutes, final List<String> items) {
        final Map<String, Object> values = new LinkedHashMap<>();
        values.put("delay-minutes", delayMinutes);
        values.put("items", items);
        return Specs.createUnsafe(HungerGamesSpec.RefillTierSpec.class, values);
    }
}
