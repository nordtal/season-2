package eu.nordtal.s2.smp.grave;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A grave settling - the moment {@code erase} removes its three entities - has to say so, out loud,
 * to everyone standing nearby (season-2-ingame/15, Till 2026-09-15: the grave used to disappear
 * silently, and should not).
 *
 * <h2>Why a text search, not a behavioural test</h2>
 * Spawning and removing real display entities and playing a real sound both need a running Paper
 * server, which this module's test suite does not have - {@link OneGraveOneWindowTest} is in the same
 * position for the same reason. This holds the shape the rest of {@code Graves}' tests already hold:
 * the source itself, rather than a mocked server.
 *
 * <p>It cannot tell whether {@code entity.skeleton.death} actually sounds right at a grave - that
 * needs a person with headphones on, standing next to one.
 */
class GraveEraseSoundTest {

    private static final String SOURCE = "smp/src/main/java/eu/nordtal/s2/smp/grave/Graves.java";

    @Test
    @DisplayName("settling a grave plays a WORLD sound, named by its own category")
    void erasingAGravePlaysAWorldSound() {
        final String source = read();

        assertTrue(source.contains("sounds.playAt("),
                "a grave finishing has to sound like something (season-2-ingame/15). play(Player, ...)"
                        + " only reaches the looter; erase runs for everyone standing at the grave, so"
                        + " this has to be the WORLD-scoped call, not the player-scoped one");

        assertTrue(source.contains("Feedback.RECLAIMED"),
                "the sound at a grave settling has to name its own category rather than reuse LOSS"
                        + " (\"something was taken from you\") or SMALL_SUCCESS (the pickup itself) -"
                        + " both already mean something else. season-2-ingame/15 calls for a twelfth"
                        + " category, RECLAIMED, for exactly this");
    }

    private static String read() {
        try {
            Path candidate = Path.of("").toAbsolutePath();
            while (candidate != null
                    && !Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) {
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
