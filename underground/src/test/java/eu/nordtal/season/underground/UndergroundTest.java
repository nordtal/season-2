package eu.nordtal.season.underground;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UndergroundTest {

    @Test
    void noScratchServerStartsWithoutTheAcceptedEula(@TempDir final Path directory) {
        final String[] words = {
            "verify",
            "--work",
            directory.toString(),
            "--server",
            "server.jar",
            "--world",
            "world",
            "--regions",
            "0,0,0,0",
            "--seed",
            "27594263"
        };

        final IllegalArgumentException refused =
                assertThrows(IllegalArgumentException.class, () -> Underground.main(words));

        assertTrue(refused.getMessage().contains("--eula true"));
    }
}
