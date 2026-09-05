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
    @DisplayName("a file that is not a 64x64 PNG is a warning and no icon, never a failed start")
    void aBadFileIsAWarning(@TempDir final Path dataDirectory) throws IOException {
        Files.writeString(dataDirectory.resolve(ServerIcon.FILE_NAME), "not a png");
        assertTrue(ServerIcon.load(dataDirectory, LoggerFactory.getLogger("test")).isEmpty());
    }
}
