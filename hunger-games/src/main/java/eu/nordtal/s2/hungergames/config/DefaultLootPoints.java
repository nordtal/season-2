package eu.nordtal.s2.hungergames.config;

import eu.nordtal.jcore.config.spec.Specs;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The five loot points a fresh {@code config.yml} is written with: the spawn, plus four staggered
 * placeholder positions at increasing distance from it.
 * <p>
 * {@code docs/hunger-games.md#loot} is explicit that the actual event world - and therefore the
 * real loot point coordinates - does not exist as a shipped asset in this repository. These
 * defaults are placeholders an operator overwrites once the hand-built world exists; they exist
 * only so a fresh install has five well-formed, uniquely labelled entries rather than the empty
 * list jcore would otherwise initialise a {@code List<NestedSpec>} to - the same problem
 * {@code DefaultTiers} in {@code discord-bot} solves for the price list, solved the same way here.
 * </p>
 */
final class DefaultLootPoints {

    static final List<HungerGamesSpec.LootPointSpec> LIST = List.of(
            point("spawn", 0, 64, 0),
            point("north", 0, 64, -150),
            point("east", 150, 64, 0),
            point("south", 0, 64, 150),
            point("west", -150, 64, 0));

    private DefaultLootPoints() {
    }

    private static HungerGamesSpec.LootPointSpec point(final String label, final double x, final double y,
                                                        final double z) {
        final Map<String, Object> values = new LinkedHashMap<>();
        values.put("label", label);
        values.put("x", x);
        values.put("y", y);
        values.put("z", z);
        return Specs.createUnsafe(HungerGamesSpec.LootPointSpec.class, values);
    }
}
