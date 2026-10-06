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

/** Icons drawn from a client jar's files: a flat item, a block in its inventory pose, and stand-ins drawn in code. */
class IconSheetTest {

    private static final int RED = 0xFFC00000;
    private static final int GREEN = 0xFF00A000;
    private static final int WOOD = 0xFF8A7040;

    private final Map<String, byte[]> files = new HashMap<>();
    private final AssetSource assets = files::get;

    @Test
    void aFlatItemIsItsTextureScaledToTheIcon() throws IOException {
        definition("ruby", "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/ruby\"}}");
        model(
                "item/ruby",
                "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"minecraft:item/ruby\"}}");
        texture("item/ruby", square(16, 4, RED));

        final BufferedImage icon = only(IconSheet.draw(assets, List.of("minecraft:ruby"), List.of()), "minecraft:ruby");

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

        final BufferedImage icon = only(IconSheet.draw(assets, List.of("minecraft:ore"), List.of()), "minecraft:ore");

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
                only(IconSheet.draw(assets, List.of("minecraft:zombie_head"), List.of()), "minecraft:zombie_head");

        assertEquals(GREEN, icon.getRGB(16, 16));
    }

    @Test
    void aBannerShowsItsPoleAndItsBarBesideTheDyedCloth() throws IOException {
        definition("red_banner", """
                {"model": {"type": "minecraft:special", "base": "minecraft:item/template_banner",
                  "model": {"type": "minecraft:banner", "color": "red"},
                  "transformation": {"scale": [0.6666667, -0.6666667, -0.6666667], "translation": [0.5, 0.0, 0.5]}}}""");
        model("item/template_banner", """
                {"display": {"gui": {"rotation": [30, 20, 0], "translation": [0, -3.25, 0],
                  "scale": [0.5325, 0.5325, 0.5325]}}}""");
        texture("entity/banner/banner_base", square(64, 0, WOOD));
        texture("entity/banner/base", square(64, 0, 0xFFFFFFFF));

        final BufferedImage icon =
                only(IconSheet.draw(assets, List.of("minecraft:red_banner"), List.of()), "minecraft:red_banner");

        int wood = 0;
        int cloth = 0;
        for (int x = 0; x < icon.getWidth(); x++) {
            for (int y = 0; y < icon.getHeight(); y++) {
                final int pixel = icon.getRGB(x, y);
                if (pixel >>> 24 != 0 && green(pixel) > blue(pixel) + 10) {
                    wood++;
                } else if (pixel >>> 24 != 0 && red(pixel) > 2 * green(pixel)) {
                    cloth++;
                }
            }
        }
        assertTrue(wood > 0, "the pole and the bar are drawn in their wood");
        assertTrue(cloth > wood, "the cloth is the largest part and takes the banner's dye: " + cloth + " " + wood);
    }

    @Test
    void anAdvancementWhoseBannerCarriesPatternsHasASlotOfItsOwnWithThemDrawnOverTheCloth() throws IOException {
        bannerDefinition("white_banner", "white");
        model("item/template_banner", """
                {"display": {"gui": {"rotation": [30, 20, 0], "translation": [0, -3.25, 0],
                  "scale": [0.5325, 0.5325, 0.5325]}}}""");
        texture("entity/banner/banner_base", square(64, 0, WOOD));
        texture("entity/banner/base", square(64, 0, 0xFFFFFFFF));
        texture("entity/banner/rhombus", square(64, 0, 0xFFFFFFFF));
        files.put("data/minecraft/banner_pattern/rhombus.json", """
                {"asset_id": "minecraft:rhombus", "translation_key": "block.minecraft.banner.rhombus"}""".getBytes(StandardCharsets.UTF_8));
        files.put("data/minecraft/advancement/adventure/hero.json", """
                {"display": {"icon": {"id": "minecraft:white_banner", "components": {"minecraft:banner_patterns": [
                  {"color": "cyan", "pattern": "minecraft:rhombus"}]}}}}""".getBytes(StandardCharsets.UTF_8));

        final GameDataStore.Icons icons = IconSheet.draw(
                assets,
                List.of("minecraft:white_banner"),
                List.of("minecraft:adventure/hero", "minecraft:adventure/none"));

        assertEquals(
                Map.of("minecraft:white_banner", 0, "minecraft:adventure/hero", 1),
                icons.index().slots(),
                "an advancement with no patterns, or no definition, keeps the plain item's icon");
        assertTrue(cyan(only(icons, "minecraft:adventure/hero")) > 0, "the cloth carries the pattern's dye");
        assertEquals(0, cyan(only(icons, "minecraft:white_banner")), "the plain banner stays undyed");
    }

    @Test
    void anItemTheJarDrawsNothingForHasNoSlot() {
        definition("ruby", "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/ruby\"}}");
        model(
                "item/ruby",
                "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"minecraft:item/ruby\"}}");
        texture("item/ruby", square(16, 0, RED));

        final GameDataStore.Icons icons =
                IconSheet.draw(assets, List.of("minecraft:air", "minecraft:ruby", "minecraft:unknown"), List.of());

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

    /** How many pixels are the cyan dye, whose blue and green well exceed its red. */
    private static int cyan(final BufferedImage icon) {
        int cyan = 0;
        for (int x = 0; x < icon.getWidth(); x++) {
            for (int y = 0; y < icon.getHeight(); y++) {
                final int pixel = icon.getRGB(x, y);
                if (pixel >>> 24 != 0 && blue(pixel) > red(pixel) + 40 && green(pixel) > red(pixel) + 40) {
                    cyan++;
                }
            }
        }
        return cyan;
    }

    private void bannerDefinition(final String item, final String colour) {
        definition(item, """
                {"model": {"type": "minecraft:special", "base": "minecraft:item/template_banner",
                  "model": {"type": "minecraft:banner", "color": "%s"},
                  "transformation": {"scale": [0.6666667, -0.6666667, -0.6666667], "translation": [0.5, 0.0, 0.5]}}}""".formatted(colour));
    }

    private static int red(final int argb) {
        return (argb >> 16) & 0xFF;
    }

    private static int green(final int argb) {
        return (argb >> 8) & 0xFF;
    }

    private static int blue(final int argb) {
        return argb & 0xFF;
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
