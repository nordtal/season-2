package eu.nordtal.s2.smp.duel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A fighter's own inventory never waits on a death screen for longer than a tick.
 *
 * <h2>The failure it exists for</h2>
 * A duel loser who is <em>dead</em> when the duel is settled cannot be written to: Minecraft
 * replaces a dead player's contents on respawn, so {@code Duels} parks their pre-duel snapshot in a
 * map and hands it back from {@code PlayerRespawnEvent} (finding 122). That map is this process's
 * memory and nothing persists it. Between the death and the click there is no time limit at all -
 * somebody who dies and walks away from the keyboard holds the snapshot for as long as they like -
 * and a restart in that window loses their own inventory for good while leaving them the arena's
 * loadout. Which is finding 122 again, reached by a different road (CodeRabbit, PR #8).
 *
 * <p>Pressing the button for them settles it on the next tick, and it is not a new decision: the
 * owner decided on 2026-09-06 that a duel ends at the spawn with a title and <b>no death screen</b>.
 * {@code onDamage} cancels the lethal blow, so this branch is reached only by {@code /kill}, the
 * void and {@code setHealth(0)} - the three ways a fighter can die without being hit, and the ones
 * that still showed the screen the decision was about.</p>
 *
 * <h2>Why a text search</h2>
 * The whole of it is one call on a live {@code Player} at the end of a real duel. What this
 * protects is that the call stays next to the map it settles - and a version without it works
 * perfectly for everybody who clicks respawn, which is everybody, until a restart lands in the
 * window.
 */
class DeadFighterIsRespawnedTest {

    private static final String SOURCE = "smp/src/main/java/eu/nordtal/s2/smp/duel/Duels.java";

    @Test
    @DisplayName("a snapshot parked for a dead fighter is claimed on the next tick, not by them")
    void theDeadFighterIsRespawned() {
        final String source = read();

        final int parked = source.indexOf("pending.put(playerId, state);");
        assertTrue(parked > 0, "Duels no longer parks a dead fighter's state - if that map is gone,"
                + " so is this rule, and this test should go with it");

        final int respawned = source.indexOf("spigot().respawn()", parked);
        assertTrue(respawned > parked && respawned - parked < 1600,
                "a dead fighter's own inventory is parked in a map nothing persists and then left"
                        + " to them to claim - a restart while they sit on the death screen loses"
                        + " it and leaves them the arena's loadout instead");
    }

    @Test
    @DisplayName("the living half is untouched: an alive fighter is restored directly")
    void theLivingFighterIsNotRespawned() {
        final String source = read();

        assertTrue(source.contains("state.restore(player, spawn());"),
                "a fighter who is still alive is restored straight away, and must not be sent"
                        + " through a respawn to get their own inventory back");
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
