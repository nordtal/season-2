package eu.nordtal.season.underground.check;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.underground.anvil.Region;
import eu.nordtal.season.underground.anvil.TestChunks;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SliceTest {

    private static final int STONE = 0x8A8A8A;
    private static final int WATER = 0x3B6FD6;
    private static final int LABEL = 26;

    @Test
    void aCutFollowsItsLineAroundACorner(@TempDir final Path directory) throws IOException {
        final Map<String, Object> chunk =
                TestChunks.chunk(0, 0, (x, y, z) -> z >= 8 ? "minecraft:water" : "minecraft:stone");
        Region.write(Region.file(directory.resolve("region"), 0, 0), new TreeMap<>(Map.of(0, chunk)));
        final Path picture = directory.resolve("cut.png");
        final Slice.Line line =
                new Slice.Line(List.of(new int[] {2, 3}, new int[] {12, 3}, new int[] {12, 13}), 60, 63, 2);

        new Slice().draw(picture, line, List.of("world"), List.of(directory));

        final BufferedImage image = ImageIO.read(picture.toFile());
        assertEquals(LABEL + 4 * 2, image.getHeight());
        assertEquals(STONE, image.getRGB(0, LABEL) & 0xFFFFFF);
        assertEquals(STONE, image.getRGB(14 * 2, LABEL + 7) & 0xFFFFFF);
        assertEquals(WATER, image.getRGB(15 * 2, LABEL) & 0xFFFFFF);
        assertEquals(WATER, image.getRGB(20 * 2 + 1, LABEL + 7) & 0xFFFFFF);
        assertEquals(0xFFFFFF, image.getRGB(21 * 2, LABEL) & 0xFFFFFF);
    }
}
