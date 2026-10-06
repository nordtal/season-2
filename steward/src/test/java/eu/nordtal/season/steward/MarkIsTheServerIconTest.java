package eu.nordtal.season.steward;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import eu.nordtal.season.common.RepositoryRoot;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/**
 * Holds the frontend's icons to {@code resource-pack/src/pack.png}, which a Vite build cannot read itself.
 *
 * The 512 icon repeats each pixel four times, nearest neighbour like {@code ServerIcon}, to keep the pixel art clean.
 */
class MarkIsTheServerIconTest {

    private static final String SOURCE = "resource-pack/src/pack.png";
    private static final String COPY = "steward/frontend/public/icon.png";
    private static final String BIG = "steward/frontend/public/icon-512.png";
    private static final String PAGE = "steward/frontend/index.html";
    private static final String MANIFEST = "steward/frontend/public/manifest.webmanifest";

    private static final int FACTOR = 4;

    @Test
    void theCopyIsTheOriginal() throws IOException {
        assertArrayEquals(
                bytes(SOURCE),
                bytes(COPY),
                COPY + " is no longer " + SOURCE + ". It is a copy because a Vite build cannot"
                        + " read across the repository - copy the file again rather than editing"
                        + " one of the two.");
    }

    @Test
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
    void thePagePointsAtThem() throws IOException {
        final String page = Files.readString(RepositoryRoot.path().resolve(PAGE), StandardCharsets.UTF_8);
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
    void theManifestIsReal() throws IOException {
        final JsonObject manifest = new Gson()
                .fromJson(
                        Files.readString(RepositoryRoot.path().resolve(MANIFEST), StandardCharsets.UTF_8),
                        JsonObject.class);

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
            final Path file = RepositoryRoot.path().resolve("steward/frontend/public" + source);
            assertTrue(Files.isRegularFile(file), "the manifest names " + source + " and there is no such file");
        }
    }

    private static byte[] bytes(final String name) throws IOException {
        final Path file = RepositoryRoot.path().resolve(name);
        assertTrue(Files.isRegularFile(file), file + " is not there.");
        return Files.readAllBytes(file);
    }

    private static BufferedImage read(final String name) throws IOException {
        final Path file = RepositoryRoot.path().resolve(name);
        assertTrue(Files.isRegularFile(file), file + " is not there.");
        final BufferedImage image = ImageIO.read(file.toFile());
        assertTrue(image != null, file + " is not a picture ImageIO can read.");
        return image;
    }
}
