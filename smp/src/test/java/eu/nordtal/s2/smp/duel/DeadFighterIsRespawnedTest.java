package eu.nordtal.s2.smp.duel;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * A fighter's own inventory never waits on a death screen for longer than a tick.
 *
 * The pre-duel snapshot lives only in memory until respawn, so the fighter is respawned on the next tick.
 */
class DeadFighterIsRespawnedTest {

    private static final String SOURCE = "smp/src/main/java/eu/nordtal/s2/smp/duel/Duels.java";

    @Test
    void theDeadFighterIsRespawned() {
        final String source = read();

        final int parked = source.indexOf("pending.put(playerId, state);");
        assertTrue(
                parked > 0,
                "Duels no longer parks a dead fighter's state - if that map is gone,"
                        + " so is this rule, and this test should go with it");

        final int respawned = source.indexOf("spigot().respawn()", parked);
        assertTrue(
                respawned > parked && respawned - parked < 1600,
                "a dead fighter's own inventory is parked in a map nothing persists and then left"
                        + " to them to claim - a restart while they sit on the death screen loses"
                        + " it and leaves them the arena's loadout instead");
    }

    @Test
    void theLivingFighterIsNotRespawned() {
        final String source = read();

        assertTrue(
                source.contains("state.restore(player, spawn());"),
                "a fighter who is still alive is restored straight away, and must not be sent"
                        + " through a respawn to get their own inventory back");
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
