package eu.nordtal.s2.proxy.ping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/** The built-in server icon is what Velocity accepts, and a fresh data folder gets a copy of it. */
class ServerIconTest {

    @Test
    void theBuiltInIconIs64By64() throws IOException {
        try (InputStream in = ServerIcon.class.getResourceAsStream("/" + ServerIcon.FILE_NAME)) {
            assertNotNull(in, "proxy's jar has to carry " + ServerIcon.FILE_NAME);
            final BufferedImage image = ImageIO.read(in);
            assertNotNull(image, "not a PNG");
            assertEquals(64, image.getWidth());
            assertEquals(64, image.getHeight());
        }
    }

    @Test
    void aFreshDataFolderIsSeeded(@TempDir final Path dataDirectory) {
        assertTrue(
                ServerIcon.load(dataDirectory, LoggerFactory.getLogger("test")).isPresent());
        assertTrue(
                Files.isRegularFile(dataDirectory.resolve(ServerIcon.FILE_NAME)),
                "the operator has to find a file to replace, not a setting to discover");
    }

    @Test
    void theBuiltInIconIsOpaque() throws IOException {
        final BufferedImage image = builtIn();
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                assertEquals(
                        255,
                        image.getRGB(x, y) >>> 24,
                        "the icon is transparent at " + x + "," + y + ". A server browser draws its"
                                + " own background through that, so the logo sits in a hole rather"
                                + " than on the dark square it was drawn on");
            }
        }
    }

    /**
     * The built-in server icon is pixel art, not a resampled image.
     *
     * The bound separates a palette from a gradient, so a redrawn logo still passes.
     */
    @Test
    void theBuiltInIconWasNotSmoothed() throws IOException {
        final BufferedImage image = builtIn();
        final java.util.Set<Integer> colours = new java.util.HashSet<>();
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                colours.add(image.getRGB(x, y));
            }
        }
        assertTrue(
                colours.size() <= 64,
                "the built-in icon carries " + colours.size() + " distinct colours, which is a"
                        + " smoothing resampler's signature rather than pixel art's. Reduce"
                        + " resource-pack/src/pack.png with nearest neighbour at 2:1 instead");
    }

    @Test
    void aBadFileIsAWarning(@TempDir final Path dataDirectory) throws IOException {
        Files.writeString(dataDirectory.resolve(ServerIcon.FILE_NAME), "not a png");
        assertTrue(
                ServerIcon.load(dataDirectory, LoggerFactory.getLogger("test")).isEmpty());
    }

    /** The icon in the jar, which every fresh data folder is seeded from. */
    private static BufferedImage builtIn() throws IOException {
        try (InputStream in = ServerIcon.class.getResourceAsStream("/" + ServerIcon.FILE_NAME)) {
            assertNotNull(in, "proxy's jar has to carry " + ServerIcon.FILE_NAME);
            final BufferedImage image = ImageIO.read(in);
            assertNotNull(image, "not a PNG");
            return image;
        }
    }
}
