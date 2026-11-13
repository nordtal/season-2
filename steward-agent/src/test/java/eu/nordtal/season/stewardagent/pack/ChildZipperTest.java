package eu.nordtal.season.stewardagent.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChildZipperTest {

    @Test
    void aFailedChildNamesItsOutputAndLeavesNoLog(@TempDir final Path work) throws IOException {
        final Path missing = work.resolve("missing.zip");

        final IOException failed = assertThrows(
                IOException.class,
                () -> new ChildZipper(Duration.ofMinutes(1))
                        .zip(missing, missing, missing, work.resolve("pack.zip"), List.of(work)));

        assertTrue(failed.getMessage().startsWith("the pack builder exited with "), failed.getMessage());
        assertTrue(failed.getMessage().contains("missing.zip"), failed.getMessage());
        try (Stream<Path> left = Files.list(work)) {
            assertEquals(List.of(), left.toList());
        }
    }
}
