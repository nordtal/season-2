package eu.nordtal.s2.networkcontrol.ping;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The built-in server icon is what Velocity accepts, and a fresh data folder gets a copy of it.
 *
 * <p>Both halves fail silently on a real proxy - a wrong-sized PNG is one warning line and a
 * browser entry without an icon, which nobody reports as a fault - so the size is pinned here
 * against the file in the jar, and the seeding against a temp directory.</p>
 */
class ServerIconTest {

    @Test
    @DisplayName("the built-in icon is a 64x64 PNG, which is the only size Velocity sends")
    void theBuiltInIconIs64By64() throws IOException {
        try (InputStream in = ServerIcon.class.getResourceAsStream("/" + ServerIcon.FILE_NAME)) {
            assertNotNull(in, "network-control's jar has to carry " + ServerIcon.FILE_NAME);
            final BufferedImage image = ImageIO.read(in);
            assertNotNull(image, "not a PNG");
            assertEquals(64, image.getWidth());
            assertEquals(64, image.getHeight());
        }
    }

    @Test
    @DisplayName("a first start copies the icon into the data folder and serves it")
    void aFreshDataFolderIsSeeded(@TempDir final Path dataDirectory) {
        assertTrue(ServerIcon.load(dataDirectory, LoggerFactory.getLogger("test")).isPresent());
        assertTrue(Files.isRegularFile(dataDirectory.resolve(ServerIcon.FILE_NAME)),
                "the operator has to find a file to replace, not a setting to discover");
    }

    @Test
    @DisplayName("the built-in icon is opaque, so it is a mark and not a hole in the list")
    void theBuiltInIconIsOpaque() throws IOException {
        final BufferedImage image = builtIn();
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                assertEquals(255, image.getRGB(x, y) >>> 24,
                        "the icon is transparent at " + x + "," + y + ". A server browser draws its"
                                + " own background through that, so the logo sits in a hole rather"
                                + " than on the dark square it was drawn on");
            }
        }
    }

    @Test
    @DisplayName("the built-in icon is pixel art reduced, not pixel art resampled")
    void theBuiltInIconWasNotSmoothed() throws IOException {
        // resource-pack/src/pack.png is 128x128 with 21 distinct colours and every 2x2 block
        // uniform - 64x64 pixel art, doubled. The icon is every second pixel of it, which is exactly
        // lossless at that ratio.
        //
        // The file that shipped until 2026-09-09 was made with `sips` instead, and its smoothing
        // turned those 21 colours into 454: a pixel logo with soft edges, which in a list of server
        // entries reads as a low-resolution photograph. Nothing about that is visible from the
        // dimensions, from the PNG being valid, or from Favicon.create accepting it - all three
        // were green on the blurred file - and it is not visible in an IDE either, at 64 px.
        //
        // The bound is loose on purpose. It is not "the logo has 21 colours", which would fail the
        // day the mark is redrawn; it is "this is a palette rather than a gradient", which is what
        // separates the two ways of getting to 64x64. A hand-painted icon with more than 64 colours
        // is a real possibility - and it would be a deliberate act, at which point this case is the
        // conversation about which resampler that art wants.
        final BufferedImage image = builtIn();
        final java.util.Set<Integer> colours = new java.util.HashSet<>();
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                colours.add(image.getRGB(x, y));
            }
        }
        assertTrue(colours.size() <= 64,
                "the built-in icon carries " + colours.size() + " distinct colours, which is a"
                        + " smoothing resampler's signature rather than pixel art's. Reduce"
                        + " resource-pack/src/pack.png with nearest neighbour at 2:1 instead");
    }

    @Test
    @DisplayName("a file that is not a 64x64 PNG is a warning and no icon, never a failed start")
    void aBadFileIsAWarning(@TempDir final Path dataDirectory) throws IOException {
        Files.writeString(dataDirectory.resolve(ServerIcon.FILE_NAME), "not a png");
        assertTrue(ServerIcon.load(dataDirectory, LoggerFactory.getLogger("test")).isEmpty());
    }

    /** The icon as it sits in the jar - the file every fresh data folder is seeded from. */
    private static BufferedImage builtIn() throws IOException {
        try (InputStream in = ServerIcon.class.getResourceAsStream("/" + ServerIcon.FILE_NAME)) {
            assertNotNull(in, "network-control's jar has to carry " + ServerIcon.FILE_NAME);
            final BufferedImage image = ImageIO.read(in);
            assertNotNull(image, "not a PNG");
            return image;
        }
    }
}
