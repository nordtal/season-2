package eu.nordtal.s2.steward.ui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Steward's mark is the server icon, and this is the test that keeps it one picture.
 *
 * <p>{@code resource-pack/src/pack.png} is what a player sees in the server browser beside the MOTD
 * (Velocity gets it through {@code ServerIcon}) and what the resource pack carries as its own
 * picture. It is now also Steward's favicon, its home-screen icon and the small mark in the sidebar.
 * Four uses, one file - except that a Vite build cannot read across the repository, so the frontend
 * needs its own copy under {@code frontend/public/}. A copy is a thing that drifts, so it is held
 * here, which is the same arrangement {@code PlatformTest} and {@code ResourcePackTest} already
 * use for the numbers they mirror.</p>
 *
 * <h2>Why the big one is nearest neighbour</h2>
 * The source is 128 x 128 pixel art in which every 2 x 2 block is one colour - 21 colours in the
 * whole image. A smoothing resampler turns those into hundreds and the mark reads as a
 * low-resolution photograph rather than as a drawing. {@code ServerIcon} halves it with nearest
 * neighbour for exactly that reason; this quadruples it with the same rule, so an iPhone that
 * scales 512 down to 180 is working from clean edges.
 *
 * <p>To make the pair again after the artwork changes: copy {@code pack.png} over
 * {@code icon.png}, and write {@code icon-512.png} by repeating each pixel four times - which is
 * the loop this test reads it back with. It names the exact pixel that disagrees.
 */
class MarkIsTheServerIconTest {

    private static final String SOURCE = "resource-pack/src/pack.png";
    private static final String COPY = "steward-ui/frontend/public/icon.png";
    private static final String BIG = "steward-ui/frontend/public/icon-512.png";
    private static final String PAGE = "steward-ui/frontend/index.html";
    private static final String MANIFEST = "steward-ui/frontend/public/manifest.webmanifest";

    private static final int FACTOR = 4;

    @Test
    @DisplayName("the favicon is the server icon, byte for byte")
    void theCopyIsTheOriginal() throws IOException {
        assertArrayEquals(
                bytes(SOURCE),
                bytes(COPY),
                COPY + " is no longer " + SOURCE + ". It is a copy because a Vite build cannot"
                        + " read across the repository - copy the file again rather than editing"
                        + " one of the two.");
    }

    @Test
    @DisplayName("the home-screen icon is that same picture, four times over, nearest neighbour")
    void theBigOneIsTheOriginalScaled() throws IOException {
        final BufferedImage source = read(SOURCE);
        final BufferedImage big = read(BIG);

        assertEquals(128, source.getWidth(), SOURCE + " is not 128 wide any more");
        assertEquals(source.getWidth() * FACTOR, big.getWidth(), BIG + " is the wrong width");
        assertEquals(source.getHeight() * FACTOR, big.getHeight(), BIG + " is the wrong height");

        for (int y = 0; y < big.getHeight(); y++) {
            for (int x = 0; x < big.getWidth(); x++) {
                final int expected = source.getRGB(x / FACTOR, y / FACTOR);
                if (big.getRGB(x, y) != expected) {
                    assertEquals(
                            String.format("%08x", expected),
                            String.format("%08x", big.getRGB(x, y)),
                            BIG + " at " + x + "," + y + " is not " + SOURCE + " at "
                                    + (x / FACTOR) + "," + (y / FACTOR) + ". It was made with a"
                                    + " smoothing resampler, or from a different picture: 21"
                                    + " colours become hundreds and the drawing reads as a"
                                    + " photograph. Repeat each pixel " + FACTOR + " times.");
                }
            }
        }
    }

    @Test
    @DisplayName("the page asks for all three, and for nothing that is not there")
    void thePagePointsAtThem() throws IOException {
        final String page = Files.readString(repository().resolve(PAGE), StandardCharsets.UTF_8);
        assertTrue(page.contains("href=\"/icon.png\""), "index.html has no favicon");
        assertTrue(
                page.contains("rel=\"apple-touch-icon\" href=\"/icon-512.png\""),
                "index.html has no apple-touch-icon, so a home screen gets a screenshot of the page");
        assertTrue(page.contains("href=\"/manifest.webmanifest\""), "index.html has no manifest");
        assertTrue(
                page.contains("viewport-fit=cover"),
                "without viewport-fit=cover the status bar style below letterboxes the page");
        assertTrue(
                page.contains("apple-mobile-web-app-capable"),
                "without this, iOS opens the home-screen icon in Safari with its address bar");
        assertTrue(!page.contains("favicon.svg"), "index.html still asks for the placeholder mark, which was deleted");
    }

    @Test
    @DisplayName("the manifest parses, and every icon in it exists")
    void theManifestIsReal() throws IOException {
        final JsonObject manifest = new Gson()
                .fromJson(Files.readString(repository().resolve(MANIFEST), StandardCharsets.UTF_8), JsonObject.class);

        assertEquals(
                "standalone",
                manifest.get("display").getAsString(),
                "anything but standalone and the home-screen icon opens Safari's chrome");
        assertEquals("/", manifest.get("scope").getAsString());
        assertEquals(
                "#0d0d0d",
                manifest.get("background_color").getAsString(),
                "the launch screen must be the interface's own background, or it flashes white");

        final JsonArray icons = manifest.getAsJsonArray("icons");
        assertTrue(icons.size() >= 2, "the manifest lists fewer than two icons");
        for (final JsonElement element : icons) {
            final String source = element.getAsJsonObject().get("src").getAsString();
            final Path file = repository().resolve("steward-ui/frontend/public" + source);
            assertTrue(Files.isRegularFile(file), "the manifest names " + source + " and there is no such file");
        }
    }

    // --- plumbing ------------------------------------------------------------------------------

    private static byte[] bytes(final String name) throws IOException {
        final Path file = repository().resolve(name);
        assertTrue(Files.isRegularFile(file), file + " is not there.");
        return Files.readAllBytes(file);
    }

    private static BufferedImage read(final String name) throws IOException {
        final Path file = repository().resolve(name);
        assertTrue(Files.isRegularFile(file), file + " is not there.");
        final BufferedImage image = ImageIO.read(file.toFile());
        assertTrue(image != null, file + " is not a picture ImageIO can read.");
        return image;
    }

    /** The repository root, found rather than assumed - a test's working directory is its module. */
    private static Path repository() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null && !Files.isRegularFile(directory.resolve("settings.gradle.kts"))) {
            directory = directory.getParent();
        }
        assertTrue(
                directory != null, "no settings.gradle.kts above " + Path.of("").toAbsolutePath());
        return directory;
    }
}
