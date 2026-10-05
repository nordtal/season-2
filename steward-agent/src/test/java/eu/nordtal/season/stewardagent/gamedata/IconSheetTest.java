package eu.nordtal.season.stewardagent.gamedata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.database.game.GameDataStore;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/** Icons drawn from a client jar's files: a flat item, a block in its inventory pose, and a head's stand-in. */
class IconSheetTest {

    private static final int RED = 0xFFC00000;
    private static final int GREEN = 0xFF00A000;

    private final Map<String, byte[]> files = new HashMap<>();
    private final AssetSource assets = files::get;

    @Test
    void aFlatItemIsItsTextureScaledToTheIcon() throws IOException {
        definition("ruby", "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/ruby\"}}");
        model(
                "item/ruby",
                "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"minecraft:item/ruby\"}}");
        texture("item/ruby", square(16, 4, RED));

        final BufferedImage icon = only(IconSheet.draw(assets, List.of("minecraft:ruby")), "minecraft:ruby");

        assertEquals(RED, icon.getRGB(8, 8));
        assertEquals(RED, icon.getRGB(23, 23));
        assertEquals(0, icon.getRGB(7, 7) >>> 24, "outside the texture's shape the icon stays clear");
        assertEquals(0, icon.getRGB(24, 24) >>> 24);
    }

    @Test
    void aBlockIsDrawnTurnedWithItsTopLighterThanItsSides() throws IOException {
        definition("ore", "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:block/ore\"}}");
        model("block/ore", """
                {"textures": {"all": "minecraft:block/ore"},
                 "display": {"gui": {"rotation": [30, 225, 0], "scale": [0.625, 0.625, 0.625]}},
                 "elements": [{"from": [0, 0, 0], "to": [16, 16, 16], "faces": {
                   "up": {"texture": "#all"}, "down": {"texture": "#all"},
                   "north": {"texture": "#all"}, "south": {"texture": "#all"},
                   "east": {"texture": "#all"}, "west": {"texture": "#all"}}}]}""");
        texture("block/ore", square(16, 0, RED));

        final BufferedImage icon = only(IconSheet.draw(assets, List.of("minecraft:ore")), "minecraft:ore");

        assertEquals(0, icon.getRGB(1, 1) >>> 24, "a turned cube leaves the corners of its icon clear");
        final int top = red(icon.getRGB(16, 8));
        final int left = red(icon.getRGB(10, 21));
        final int right = red(icon.getRGB(22, 21));
        assertTrue(top > left && left > right, "lit from above and the left: " + top + " " + left + " " + right);
    }

    @Test
    void aHeadTheGameDrawsInCodeShowsItsMobsFace() throws IOException {
        definition("zombie_head", """
                {"model": {"type": "minecraft:special", "base": "minecraft:item/template_skull",
                  "model": {"type": "minecraft:head", "kind": "zombie"}}}""");
        final BufferedImage skin = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        for (int x = 8; x < 16; x++) {
            for (int y = 8; y < 16; y++) {
                skin.setRGB(x, y, GREEN);
            }
        }
        texture("entity/zombie/zombie", skin);

        final BufferedImage icon =
                only(IconSheet.draw(assets, List.of("minecraft:zombie_head")), "minecraft:zombie_head");

        assertEquals(GREEN, icon.getRGB(16, 16));
    }

    @Test
    void anItemTheJarDrawsNothingForHasNoSlot() {
        definition("ruby", "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/ruby\"}}");
        model(
                "item/ruby",
                "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"minecraft:item/ruby\"}}");
        texture("item/ruby", square(16, 0, RED));

        final GameDataStore.Icons icons =
                IconSheet.draw(assets, List.of("minecraft:air", "minecraft:ruby", "minecraft:unknown"));

        assertEquals(Map.of("minecraft:ruby", 0), icons.index().slots());
        assertEquals(IconSheet.COLUMNS, icons.index().columns());
        assertFalse(icons.index().slots().containsKey("minecraft:air"));
    }

    private BufferedImage only(final GameDataStore.Icons icons, final String item) throws IOException {
        final int slot = icons.index().slots().get(item);
        final BufferedImage sheet = ImageIO.read(new ByteArrayInputStream(icons.png()));
        assertEquals(IconSheet.COLUMNS * Raster.ICON, sheet.getWidth());
        return sheet.getSubimage(
                (slot % IconSheet.COLUMNS) * Raster.ICON,
                (slot / IconSheet.COLUMNS) * Raster.ICON,
                Raster.ICON,
                Raster.ICON);
    }

    private static int red(final int argb) {
        return (argb >> 16) & 0xFF;
    }

    private void definition(final String item, final String json) {
        files.put("assets/minecraft/items/" + item + ".json", json.getBytes(StandardCharsets.UTF_8));
    }

    private void model(final String path, final String json) {
        files.put("assets/minecraft/models/" + path + ".json", json.getBytes(StandardCharsets.UTF_8));
    }

    private void texture(final String path, final BufferedImage image) {
        final ByteArrayOutputStream png = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", png);
        } catch (final IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
        files.put("assets/minecraft/textures/" + path + ".png", png.toByteArray());
    }

    /** A square texture of one colour with a clear border {@code inset} pixels wide. */
    private static BufferedImage square(final int size, final int inset, final int argb) {
        final BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        for (int x = inset; x < size - inset; x++) {
            for (int y = inset; y < size - inset; y++) {
                image.setRGB(x, y, argb);
            }
        }
        return image;
    }
}
