package eu.nordtal.season.packbuilder.wheel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.packbuilder.vanilla.VanillaFacts;
import eu.nordtal.season.packbuilder.zip.ReproducibleZip;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WheelPackTest {

    @Test
    void aKeyIsTheItemIdWithoutItsNamespaceAndComponents() {
        assertEquals("diamond_sword", WheelPack.key("minecraft:diamond_sword[enchantments={sharpness:5}]"));
        assertEquals("dirt", WheelPack.key("minecraft:dirt"));
        assertEquals("nordtal:coin", WheelPack.key("nordtal:coin"));
    }

    @Test
    void theCountsCoverEachListedAmountAboveOne() {
        final List<Integer> expected = List.of(32, 128);

        final List<Integer> amounts = WheelPack.amounts(List.of(
                new WheelPack.Prize("dirt", 1), new WheelPack.Prize("dirt", 32), new WheelPack.Prize("dirt", 128)));

        assertEquals(expected, amounts);
    }

    @Test
    void anItemVanillaLacksIsLeftOutAndTurnsAsThePlaceholder(@TempDir final Path dir) throws IOException {
        final Path facts = dir.resolve("facts.zip");
        Files.write(
                facts,
                ReproducibleZip.write(Map.of(
                        VanillaFacts.ITEMS + "dirt.json",
                        bytes("{\"model\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/dirt\"}}"),
                        "assets/minecraft/models/item/dirt.json",
                        bytes("{\"display\":{\"gui\":{\"scale\":[0.5,0.5,0.5]}}}"))));
        final WheelPack pack = new WheelPack(
                VanillaFacts.read(facts),
                List.of(new WheelPack.Prize("dirt", 2), new WheelPack.Prize("no_such_item", 3)),
                Map.of("de", "Aus"));

        final String prize =
                new String(pack.items(List.of(0)).get("assets/nordtal/items/wheel/p0.json"), StandardCharsets.UTF_8);

        assertTrue(prize.contains("\"dirt\""), prize);
        assertFalse(prize.contains("no_such_item"), prize);
        assertTrue(prize.contains("nordtal:item/wheel/unknown"), prize);
    }

    @Test
    void anItemThatDrawsNothingInASlotTurnsAsThePlaceholder(@TempDir final Path dir) throws IOException {
        final Path facts = dir.resolve("facts.zip");
        Files.write(
                facts,
                ReproducibleZip.write(Map.of(
                        VanillaFacts.ITEMS + "dirt.json",
                        bytes("{\"model\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/dirt\"}}"),
                        "assets/minecraft/models/item/dirt.json",
                        bytes("{\"display\":{\"gui\":{\"scale\":[0.5,0.5,0.5]}}}"),
                        VanillaFacts.ITEMS + "air.json",
                        bytes("{\"model\":{\"type\":\"minecraft:empty\"}}"))));
        final WheelPack pack = new WheelPack(
                VanillaFacts.read(facts),
                List.of(new WheelPack.Prize("dirt", 2), new WheelPack.Prize("air", 3)),
                Map.of("de", "Aus"));

        final String prize =
                new String(pack.items(List.of(0)).get("assets/nordtal/items/wheel/p0.json"), StandardCharsets.UTF_8);

        assertFalse(prize.contains("\"air\""), prize);
        assertTrue(prize.contains("nordtal:item/wheel/unknown"), prize);
    }

    private static byte[] bytes(final String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
