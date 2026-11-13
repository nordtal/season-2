package eu.nordtal.season.packbuilder.wheel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WheelVanillaTest {

    private static final Map<String, Object> SHIELD =
            Map.of("type", "minecraft:model", "model", "minecraft:item/shield");

    @Test
    void aLeafTakesTheGuiCaseAndCarriesTheCollapsedTransformation() {
        final Map<String, Object> node = Map.of(
                "type", "minecraft:condition",
                "transformation", List.of(1, 0, 0, 2, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1),
                "on_true", Map.of("type", "minecraft:empty"),
                "on_false",
                        Map.of(
                                "type",
                                "minecraft:select",
                                "property",
                                "minecraft:display_context",
                                "cases",
                                List.of(Map.of("when", List.of("gui", "fixed"), "model", SHIELD)),
                                "fallback",
                                Map.of("type", "minecraft:empty")));
        final Map<String, Object> leaf = WheelVanilla.leaf(node);
        assertEquals("minecraft:item/shield", leaf.get("model"));
        assertEquals(2.0, ((Number) ((List<?>) leaf.get("transformation")).get(3)).doubleValue(), 1e-9);
    }

    @Test
    void aRangeAndAnyOtherSelectReduceToTheirFallback() {
        final Map<String, Object> range = Map.of(
                "type",
                "minecraft:range_dispatch",
                "property",
                "minecraft:count",
                "entries",
                List.of(Map.of("threshold", 1, "model", Map.of("type", "minecraft:empty"))),
                "fallback",
                SHIELD);
        assertEquals(SHIELD, WheelVanilla.leaf(range));
        final Map<String, Object> select = Map.of(
                "type",
                "minecraft:select",
                "property",
                "minecraft:charge_type",
                "cases",
                List.of(Map.of("when", "arrow", "model", Map.of("type", "minecraft:empty"))),
                "fallback",
                SHIELD);
        assertEquals(SHIELD, WheelVanilla.leaf(select));
    }

    @Test
    void aCompositeKeepsEveryPartReduced() {
        final Map<String, Object> composite = Map.of(
                "type",
                "minecraft:composite",
                "models",
                List.of(SHIELD, Map.of("type", "minecraft:condition", "on_false", SHIELD)));
        final Map<String, Object> leaf = WheelVanilla.leaf(composite);
        assertEquals(List.of(SHIELD, SHIELD), leaf.get("models"));
        assertEquals(List.of("minecraft:item/shield", "minecraft:item/shield"), WheelVanilla.refs(leaf));
    }
}
