package eu.nordtal.s2.hungergames.config;

import eu.nordtal.jcore.config.spec.Specs;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The five loot points a fresh {@code config.yml} is written with: placeholders an operator
 * overwrites once the hand-built event world exists. They exist so a fresh install has five
 * well-formed, uniquely labelled entries rather than the empty list jcore would otherwise
 * initialise a {@code List<NestedSpec>} to.
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
