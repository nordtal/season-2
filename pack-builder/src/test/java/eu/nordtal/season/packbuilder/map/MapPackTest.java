package eu.nordtal.season.packbuilder.map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.Platform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.IntBinaryOperator;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MapPackTest {

    @TempDir
    Path tiles;

    /** Writes a level-1 tile whose block at x and z has {@code colour} and {@code height}. */
    private void tile(final int tx, final int tz, final IntBinaryOperator colour, final IntBinaryOperator height)
            throws IOException {
        final BufferedImage image =
                new BufferedImage(MapRender.TILE + 1, MapRender.META + MapRender.TILE + 1, BufferedImage.TYPE_INT_ARGB);
        for (int z = 0; z < MapRender.TILE; z++) {
            for (int x = 0; x < MapRender.TILE; x++) {
                image.setRGB(x, z, colour.applyAsInt(x, z));
                final int stored = height.applyAsInt(x, z) & 0xFFFF;
                image.setRGB(x, MapRender.META + z, 0xFF000000 | 15 << 16 | stored);
            }
        }
        final Path file = tiles.resolve("1").resolve(MapRender.path("x", tx)).resolve(MapRender.path("z", tz) + ".png");
        Files.createDirectories(file.getParent());
        ImageIO.write(image, "png", file.toFile());
    }

    @Test
    void theWholePlayAreaGivesAnImageOf1248PixelsAt8AndOf624At16() throws IOException {
        final Map<Integer, BufferedImage> images = MapRender.draw(tiles, MapArea.PLAY, MapPack.SCALES);

        assertEquals(List.of(8, 16), List.copyOf(images.keySet()));
        assertEquals(1248, images.get(8).getWidth());
        assertEquals(1248, images.get(8).getHeight());
        assertEquals(624, images.get(16).getWidth());
        assertEquals(624, images.get(16).getHeight());
    }

    @Test
    void everyGlyphOfBothImagesIsInThePackEvenWhereNoTileExists() throws IOException {
        final Map<String, byte[]> files = MapPack.files(MapRender.draw(tiles, MapArea.PLAY, MapPack.SCALES));

        assertEquals(100, textures(files, 8));
        assertEquals(25, textures(files, 16));
        assertEquals(100, count(text(files, "assets/nordtal/font/map_8.json"), "\"type\":\"bitmap\""));
        assertEquals(25, count(text(files, "assets/nordtal/font/map_16.json"), "\"type\":\"bitmap\""));
        final BufferedImage corner =
                ImageIO.read(new ByteArrayInputStream(files.get("assets/nordtal/textures/map/8/9_9.png")));
        assertEquals(MapPack.GLYPH, corner.getWidth());
        assertEquals(0, corner.getRGB(0, 0) >>> 24);
    }

    @Test
    void aPixelAveragesTheColourOfItsBlocks() throws IOException {
        tile(0, 0, (x, z) -> (x + z) % 2 == 0 ? 0xFF000000 | 200 << 16 : 0xFF000000 | 100 << 8, (x, z) -> 64);

        final BufferedImage image =
                MapRender.draw(tiles, new MapArea(0, 0, 64), List.of(8)).get(8);

        assertEquals(8, image.getWidth());
        assertEquals(0xFF000000 | 100 << 16 | 50 << 8, image.getRGB(3, 3));
    }

    @Test
    void aSlopeRisingToTheSouthEastIsLitAndOneFallingIsShaded() throws IOException {
        tile(0, 0, (x, z) -> 0xFF808080, (x, z) -> x < 32 ? 64 : x < 48 ? 64 + (x - 32) : 80 - (x - 48));

        final BufferedImage image =
                MapRender.draw(tiles, new MapArea(0, 0, 64), List.of(8)).get(8);

        assertEquals(0x80, image.getRGB(1, 1) & 0xFF);
        assertTrue((image.getRGB(5, 1) & 0xFF) > 0x80);
        assertTrue((image.getRGB(7, 1) & 0xFF) < 0x80);
    }

    @Test
    void blocksWithoutATileOrWithoutColourStayTransparent() throws IOException {
        tile(0, 0, (x, z) -> z < 8 ? 0 : 0xFF336699, (x, z) -> 64);

        final BufferedImage image =
                MapRender.draw(tiles, new MapArea(0, 0, 512), List.of(8)).get(8);

        assertEquals(0, image.getRGB(0, 0) >>> 24);
        assertEquals(0xFF336699, image.getRGB(0, 1));
        assertEquals(0, image.getRGB(63, 63) >>> 24);
    }

    @Test
    void aTileThatIsNotBlueMapsLowResolutionTileIsRefused() throws IOException {
        final Path file = tiles.resolve("1/x0/z0.png");
        Files.createDirectories(file.getParent());
        ImageIO.write(new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB), "png", file.toFile());

        assertThrows(IOException.class, () -> MapRender.draw(tiles, new MapArea(0, 0, 64), List.of(8)));
    }

    @Test
    void theSameTilesGiveTheSameZip() throws IOException {
        tile(0, 0, (x, z) -> 0xFF000000 | x << 8 | z % 256, (x, z) -> z / 4);
        tile(19, 19, (x, z) -> 0xFF00AA00, (x, z) -> -3);

        final byte[] first = MapPack.build(tiles);

        assertArrayEquals(first, MapPack.build(tiles));
        tile(19, 19, (x, z) -> 0xFF00AB00, (x, z) -> -3);
        assertFalse(Arrays.equals(first, MapPack.build(tiles)));
    }

    @Test
    void thePackTakesThePlatformsFormat() throws IOException {
        final String mcmeta = text(MapPack.files(Map.of()), "pack.mcmeta");

        assertTrue(mcmeta.contains("\"min_format\":" + Platform.PACK_FORMAT));
        assertTrue(mcmeta.contains("\"max_format\":" + Platform.PACK_FORMAT));
    }

    @Test
    void glyphsAreNumberedRowByRowFromTheNorthWest() {
        assertEquals(0xF0000, MapPack.codepoint(10, 0, 0));
        assertEquals(0xF0009, MapPack.codepoint(10, 9, 0));
        assertEquals(0xF000A, MapPack.codepoint(10, 0, 1));
    }

    @Test
    void aTileFolderHasOneLevelForEachDigit() {
        assertEquals("x1/9", MapRender.path("x", 19));
        assertEquals("z0", MapRender.path("z", 0));
        assertEquals("x-1/2", MapRender.path("x", -12));
    }

    @Test
    void aHeightReadsGreenAndBlueAsOneNumberBelowZeroFromHalfway() {
        assertEquals(258, MapRender.height(0xFF0F0102));
        assertEquals(-2, MapRender.height(0xFF0FFFFD));
    }

    private static long textures(final Map<String, byte[]> files, final int scale) {
        return files.keySet().stream()
                .filter(path -> path.startsWith("assets/nordtal/textures/map/" + scale + "/"))
                .count();
    }

    private static String text(final Map<String, byte[]> files, final String path) {
        return new String(files.get(path), StandardCharsets.UTF_8);
    }

    private static int count(final String text, final String part) {
        return text.split(Pattern.quote(part), -1).length - 1;
    }
}
