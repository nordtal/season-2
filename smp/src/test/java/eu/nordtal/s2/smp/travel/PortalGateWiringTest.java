package eu.nordtal.s2.smp.travel;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The one thing about the portal gate that only a running server could otherwise answer.
 *
 * <p>Cancelling {@code PortalCreateEvent} stops the portal and nothing else: the flint and steel
 * has already placed a fire block, and vanilla leaves it standing. So a refused ignition burns the
 * player who just read the refusal.
 *
 * <p>A text search, because raising a real {@code PortalCreateEvent} needs a world, a frame and an
 * ignition. What it protects is one call next to the cancel, which a later edit can drop with no
 * visible symptom.
 */
class PortalGateWiringTest {

    private static final String SOURCE = "smp/src/main/java/eu/nordtal/s2/smp/travel/PortalGate.java";

    // theFarmWorldExitExists stood here until 2026-09-20. It held the EntityPortalEnterEvent
    // handler that carried a player out of the farm world, which vanilla's PlayerPortalEvent
    // never reaches for a custom dimension. The farm world went with season-2-ingame/30 and the
    // handler with it.

    @Test
    @DisplayName("cancelling the portal also puts out the fire the ignition left")
    void theRefusalClearsUpAfterItself() {
        final String source = read();

        final int cancelled = source.indexOf("event.setCancelled(true);\n        putOutTheFire(");
        assertTrue(
                cancelled > 0,
                "the Nether gate cancels the portal without clearing the fire the flint and steel"
                        + " already placed, so a refused ignition burns the player who read the"
                        + " refusal");
        assertTrue(
                source.contains("runTask(plugin,"),
                "the fire is cleared in the same call stack that placed it, which is undone -"
                        + " it has to happen on the next tick");
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
