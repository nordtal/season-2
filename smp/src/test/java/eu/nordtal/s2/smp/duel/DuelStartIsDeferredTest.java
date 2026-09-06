package eu.nordtal.s2.smp.duel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That a duel does not begin inside the move event that started it.
 *
 * <p>A text search, because the failure needs two real players on a real server and there is no
 * Bukkit here to fake. What it guards is a trap with no symptom: {@code PlayerMoveEvent} applies
 * {@code event.getTo()} to the player <em>after</em> every handler has returned, so a teleport
 * performed inside one is undone again - for that player only. The duel's own check does not see
 * it, because {@code Player#teleport} returned {@code true}.</p>
 *
 * <p>What it produced on the local stack on 2026-09-06: the fighter who was already waiting was
 * moved into the arena at y=201, the fighter who had just stepped onto the platform stayed at
 * y=68 in ADVENTURE mode holding the free loadout, and the duel ran on regardless. Finding 119.</p>
 */
class DuelStartIsDeferredTest {

    @Test
    @DisplayName("the duel is started on the next tick, not inside the move event")
    void theStartIsScheduled() throws IOException {
        final String source = read("smp/src/main/java/eu/nordtal/s2/smp/duel/Duels.java");
        final int steppedOn = source.indexOf("public void steppedOn(");
        assertTrue(steppedOn >= 0, "Duels has no steppedOn");
        final String body = source.substring(steppedOn, source.indexOf("\n    }\n", steppedOn));
        assertTrue(body.contains("Bukkit.getScheduler().runTask("),
                "steppedOn calls begin() straight from the move event, so whichever fighter is"
                        + " standing in that event is teleported into the arena and immediately"
                        + " put back on the platform - with the loadout, in a scored duel");
        assertTrue(body.contains("begin(first, second, type)"),
                "the scheduled task no longer starts the duel");
    }

    @Test
    @DisplayName("a duel death leaves the arena on the next tick, not inside the death event")
    void theEndIsScheduled() throws IOException {
        // The mirror image of the case above, and it cost the loser twice. GraveListener asks
        // "is this player in an arena?" at HIGH, to skip both the grave and the death penalty;
        // DuelListener runs at LOWEST and used to end the duel there, so by the time the question
        // was asked the answer was no. A duel death booked DUEL_LOSS -10 and DEATH -5 in the same
        // millisecond, in the one place docs/smp.md says a death costs nothing. Finding 120.
        final String source = read("smp/src/main/java/eu/nordtal/s2/smp/duel/DuelListener.java");
        final int death = source.indexOf("public void onDeath(");
        assertTrue(death >= 0, "DuelListener has no onDeath");
        final String body = source.substring(death, source.indexOf("\n    }\n", death));
        assertTrue(body.contains("runTask(plugin, () -> duels.decide("),
                "DuelListener#onDeath decides the duel inside the death event, so the arena is"
                        + " already forgotten when GraveListener asks about it at HIGH");
    }

    @Test
    @DisplayName("a dead fighter's own inventory waits for the respawn")
    void theLosersStateWaitsForTheRespawn() throws IOException {
        // The worst of the four, and the one the rehearsal was told to watch for: the loser of a
        // duel is DEAD when the duel is settled, and an inventory written onto a dead player is
        // thrown away by the respawn, which hands back whatever they died holding - the arena's
        // loadout. So the loser walked off with a free iron sword and a shield and their own
        // inventory was gone. Thirteen emeralds, in the run that found it. Finding 122.
        final String duels = read("smp/src/main/java/eu/nordtal/s2/smp/duel/Duels.java");
        assertTrue(duels.contains("if (player.isDead()) {"),
                "Duels#restore writes onto a dead player, and the respawn throws it away");
        assertTrue(duels.contains("public void respawned("),
                "nothing hands the state back on the other side of the respawn screen");
        assertTrue(read("smp/src/main/java/eu/nordtal/s2/smp/duel/DuelListener.java")
                        .contains("duels.respawned(event)"),
                "DuelListener does not listen for the respawn, so the state waits for ever and the"
                        + " loser keeps the loadout - which is worse than not saving it at all");
    }

    @Test
    @DisplayName("a duel ends without a death screen, at the spawn, with a title")
    void theOutcomeIsShownWithoutADeath() throws IOException {
        // Decided by the owner on 2026-09-06 after watching one: the loser saw the ordinary red
        // "You Died!" screen for a sparring match that costs nothing. The only way to avoid it is
        // to cancel the blow before it lands - a death cannot be un-shown - so the arena's lethal
        // damage ends the duel instead of killing anybody.
        final String listener = read("smp/src/main/java/eu/nordtal/s2/smp/duel/DuelListener.java");
        assertTrue(listener.contains("public void onDamage("),
                "nothing catches the lethal blow, so every duel still ends on a death screen");
        assertTrue(listener.contains("event.setCancelled(true)"),
                "the lethal blow is not cancelled, so the death happens anyway");

        final String duels = read("smp/src/main/java/eu/nordtal/s2/smp/duel/Duels.java");
        assertTrue(duels.contains("state.restore(player, spawn())"),
                "a fighter is put back on the platform rather than at the spawn");
        assertTrue(duels.contains("player.showTitle("),
                "the outcome is only a chat line, and the person who just lost is not reading chat");
    }

    private static String read(final String relative) throws IOException {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null && !Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) {
            candidate = candidate.getParent();
        }
        if (candidate == null) {
            throw new IllegalStateException("no settings.gradle.kts above " + Path.of("").toAbsolutePath());
        }
        final Path path = candidate.resolve(relative);
        assertTrue(Files.isRegularFile(path), relative + " no longer exists");
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
