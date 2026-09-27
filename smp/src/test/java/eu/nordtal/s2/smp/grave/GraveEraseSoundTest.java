package eu.nordtal.s2.smp.grave;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * A grave settling plays a sound to everyone nearby when {@code erase} removes its entities.
 *
 * A text search, since display entities and sounds need a running server.
 */
class GraveEraseSoundTest {

    private static final String SOURCE = "smp/src/main/java/eu/nordtal/s2/smp/grave/Graves.java";

    @Test
    void erasingAGravePlaysAWorldSound() {
        final String source = read();

        assertTrue(
                source.contains("sounds.playAt("),
                "a grave finishing has to sound like something. play(Player, ...) only reaches the"
                        + " looter; erase runs for everyone standing at the grave, so this has to be"
                        + " the WORLD-scoped call, not the player-scoped one");

        assertTrue(
                source.contains("Feedback.RECLAIMED"),
                "the sound at a grave settling has to name its own category rather than reuse LOSS"
                        + " (\"something was taken from you\") or SMALL_SUCCESS (the pickup itself) -"
                        + " both already mean something else");
    }

    private static String read() {
        try {
            Path candidate = Path.of("").toAbsolutePath();
            while (candidate != null && !Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) {
                candidate = candidate.getParent();
            }
            if (candidate == null) {
                throw new IllegalStateException("no settings.gradle.kts above the working directory");
            }
            final Path source = candidate.resolve(SOURCE);
            assertTrue(Files.isRegularFile(source), SOURCE + " no longer exists");
            return Files.readString(source, StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot read " + SOURCE, e);
        }
    }
}
