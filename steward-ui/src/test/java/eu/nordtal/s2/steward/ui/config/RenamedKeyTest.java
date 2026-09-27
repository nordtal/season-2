package eu.nordtal.s2.steward.ui.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.jcore.config.ConfigLoader;
import eu.nordtal.jcore.config.exception.ConfigException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a {@code steward-ui.yml} written before a key was renamed loads as, asked of the real loader.
 *
 * jcore preserves a written file, so this decides whether a rename is a deployment step or only a commit.
 */
class RenamedKeyTest {

    @TempDir
    Path directory;

    @Test
    void anOldFileGetsTheNewKey() throws IOException, ConfigException {
        // `session-days` is absent and `session-hours` is not.
        final Path file = directory.resolve("steward-ui.yml");
        Files.writeString(file, """
                port: 8080
                public-url: https://steward.dev.nordtal.eu
                session-hours: 12
                """);

        final UiSpec config = ConfigLoader.builder(file, UiSpec.class).load().get();

        assertEquals(
                30,
                config.sessionDays(),
                "a preserved file with no session-days must fall back to the spec's default, not"
                        + " to zero - a zero would sign everybody out on the redirect that signed"
                        + " them in");

        final String after = Files.readString(file);
        assertTrue(
                after.contains("session-days: 30"),
                "the loader did not write the new key into"
                        + " the preserved file, so correcting it by hand is a deployment step and the"
                        + " comment in UiSpec has to say so. The file now reads:\n" + after);

        // The chosen value is not carried over: 12 hours does not become 12 days or half a day.
        assertEquals(30, config.sessionDays());
    }
}
