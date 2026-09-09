package eu.nordtal.s2.hungergames.config;

import eu.nordtal.jcore.config.spec.Specs;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The four refill tiers a fresh {@code config.yml} is written with (0h / 1h / 2h / 2h30), running
 * basic to overpowered. Each pool stays small: these are the contents of one shared chest at each
 * point, restocked with one of each material, not a loot table with quantities or randomness.
 */
final class DefaultRefillTiers {

    static final List<HungerGamesSpec.RefillTierSpec> LIST = List.of(
            // 0h00 - basic, farming-oriented. The shield keeps an early fight from being a free
            // kill for whoever finds a sword first.
            tier(0, List.of(
                    "WOODEN_AXE", "STONE_SWORD", "SHIELD", "BREAD", "APPLE", "WHEAT_SEEDS")),
            // 1h00 - iron-level PvP gear.
            tier(60, List.of(
                    "IRON_SWORD", "IRON_HELMET", "IRON_CHESTPLATE", "IRON_LEGGINGS", "IRON_BOOTS",
                    "BOW", "ARROW", "COOKED_BEEF", "GOLDEN_CARROT")),
            // 2h00 - diamond-level gear.
            tier(120, List.of(
                    "DIAMOND_SWORD", "DIAMOND_CHESTPLATE", "CROSSBOW", "SPECTRAL_ARROW",
                    "GOLDEN_APPLE", "SHIELD", "ENDER_PEARL")),
            // 2h30 - overpowered. Half an hour after diamond, so the last stretch of the game
            // rewards moving between points rather than camping one chest.
            tier(150, List.of(
                    "NETHERITE_SWORD", "NETHERITE_CHESTPLATE", "ENCHANTED_GOLDEN_APPLE",
                    "TOTEM_OF_UNDYING", "SPLASH_POTION")));

    private DefaultRefillTiers() {
    }

    private static HungerGamesSpec.RefillTierSpec tier(final int delayMinutes, final List<String> items) {
        final Map<String, Object> values = new LinkedHashMap<>();
        values.put("delay-minutes", delayMinutes);
        values.put("items", items);
        return Specs.createUnsafe(HungerGamesSpec.RefillTierSpec.class, values);
    }
}
