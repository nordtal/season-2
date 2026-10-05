package eu.nordtal.season.stewardagent.measure;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The real {@code du}, against a file of a known size. */
class VolumeSizesTest {

    @TempDir
    Path root;

    @Test
    void theRealDuCountsWhatIsOnTheDisk() throws IOException {
        Files.write(root.resolve("world.dat"), new byte[256 * 1024]);
        final long bytes = VolumeSizes.du(root).orElseThrow();
        assertTrue(bytes >= 256 * 1024, "du said " + bytes);
    }
}
