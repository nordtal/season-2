package eu.nordtal.season.hungergames.config;

import eu.nordtal.season.spec.Specs;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The five placeholder loot points the {@code config} group defaults to, until the event world exists. */
final class DefaultLootPoints {

    static final List<HungerGamesSpec.LootPointSpec> LIST = List.of(
            point("spawn", 0, 64, 0),
            point("north", 0, 64, -150),
            point("east", 150, 64, 0),
            point("south", 0, 64, 150),
            point("west", -150, 64, 0));

    private DefaultLootPoints() {}

    private static HungerGamesSpec.LootPointSpec point(
            final String label, final double x, final double y, final double z) {
        final Map<String, Object> values = new LinkedHashMap<>();
        values.put("label", label);
        values.put("x", x);
        values.put("y", y);
        values.put("z", z);
        return Specs.createUnsafe(HungerGamesSpec.LootPointSpec.class, values);
    }
}
