package eu.nordtal.s2.smp.travel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two things about the portal gate that only a running server could otherwise answer.
 *
 * <h2>The failure it exists for</h2>
 * Cancelling {@code PortalCreateEvent} stops the portal and nothing else. The flint and steel has
 * already placed a fire block by the time the event is raised, and vanilla leaves it standing
 * because to vanilla the frame was simply invalid. Here the frame is valid and the <em>server</em>
 * refused - so the player read "the Nether is not open yet" while standing in the flames losing
 * hearts, and a death there would have cost five aura on top (finding 130). The refusal charged
 * them for the thing it had just refused.
 *
 * <h2>Why a text search</h2>
 * Raising a real {@code PortalCreateEvent} needs a world, a frame and an ignition. What this
 * protects is one call sitting next to the cancel, which is exactly the kind of line a later edit
 * drops without noticing - the command still works, the message still arrives, and only somebody
 * standing in the portal can tell.
 */
class PortalGateWiringTest {

    private static final String SOURCE =
            "smp/src/main/java/eu/nordtal/s2/smp/travel/PortalGate.java";

    @Test
    @DisplayName("the farm world's exit hangs off the event that actually arrives there")
    void theFarmWorldExitExists() {
        // docs/smp.md: every portal in the farm world leads to the Nordtal spawn. PlayerPortalEvent
        // is never raised there - the farm world is a custom dimension and vanilla links only
        // overworld to nether - so the branch in onPortal is unreachable and a player stood in a
        // purple screen for as long as they liked (finding 131). EntityPortalEnterEvent is raised
        // from the block rather than from the travel logic, which is why it arrives at all.
        final String source = read();

        assertTrue(source.contains("EntityPortalEnterEvent"),
                "nothing carries a player out of the farm world: PlayerPortalEvent does not arrive"
                        + " for a custom dimension, so the exit has to hang off the portal block");
        assertTrue(source.contains("LandingSite.safeAt("),
                "the farm world's exit drops the player at a raw spawn location, which finding 124"
                        + " already showed is a coordinate rather than a promise");
        assertTrue(source.contains("leaving.add(player.getUniqueId())"),
                "EntityPortalEnterEvent fires every tick the player is in the portal, so the"
                        + " countdown has to be armed once rather than once per tick");
    }

    @Test
    @DisplayName("cancelling the portal also puts out the fire the ignition left")
    void theRefusalClearsUpAfterItself() {
        final String source = read();

        final int cancelled = source.indexOf("event.setCancelled(true);\n        putOutTheFire(");
        assertTrue(cancelled > 0,
                "the Nether gate cancels the portal without clearing the fire the flint and steel"
                        + " already placed, so a refused ignition burns the player who read the"
                        + " refusal");
        assertTrue(source.contains("runTask(plugin,"),
                "the fire is cleared in the same call stack that placed it, which is undone -"
                        + " it has to happen on the next tick");
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
