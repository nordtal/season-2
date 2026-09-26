package eu.nordtal.s2.smp.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The figure in the tavern survives being hit, and the guard that makes that true is switched on.
 *
 * <b>The failure it exists for</b>
 *
 * {@code SpawnNpc} has set {@code setInvulnerable(true)} since the day it was written, and that flag is not what it
 * reads as: vanilla lets a <b>creative-mode</b> player hit straight through it, and the <b>void</b> ignores it
 * outright. Every admin standing next to the NPC is in creative mode. The spawn protection is a rule about blocks,
 * so it defends nothing here. The figure is the only way a {@code HAND_IN} objective can be fulfilled, so the whole
 * milestone track goes with it and nothing says so - the next player to right-click simply finds nothing to click
 * (finding 150).
 *
 * <b>Why the registration is half the test</b>
 *
 * A test that read {@code NpcProtection} alone would have been green on the defect this is written for: the handlers
 * can be perfect and never run. The listener is one line in {@code SmpPlugin}, in a method with twenty of them, and
 * it is exactly the line a merge drops. {@code AdminWatchWiringTest} in {@code :common} exists for the same shape of
 * failure - a mechanism that was written, was tested, and had no caller for a day.
 *
 * <b>Why a text search</b>
 *
 * Raising an {@code EntityDamageEvent} needs a world, an entity and a damage source, and spawning a
 * {@code Mannequin} needs a server. What is being protected is a registration and three cancels, which is the same
 * reason {@code PortalGateWiringTest} and {@code SpawnNpcLabelTest} are text searches.
 */
class NpcSurvivesTest {

    private static final String GUARD = "smp/src/main/java/eu/nordtal/s2/smp/npc/NpcProtection.java";
    private static final String FIGURE = "smp/src/main/java/eu/nordtal/s2/smp/npc/SpawnNpc.java";
    private static final String PLUGIN = "smp/src/main/java/eu/nordtal/s2/smp/SmpPlugin.java";
    // SmpStart holds the start sequence SmpPlugin delegates to, so the wiring is read from both.
    private static final String START = "smp/src/main/java/eu/nordtal/s2/smp/SmpStart.java";

    @Test
    void theGuardRefusesTheThreeWaysToLoseIt() {
        final String guard = read(GUARD);

        assertTrue(
                guard.contains("EntityDamageEvent"),
                "nothing cancels damage to the NPC, so a creative-mode hit or a step into the void"
                        + " removes the only entity a HAND_IN objective can be handed to");
        assertTrue(
                guard.contains("EntityCombustEvent"),
                "the NPC can still catch fire. With damage cancelled it would burn for the rest of"
                        + " the season without ever dying, which reads as broken to everybody who"
                        + " walks past the tavern");
        assertTrue(
                guard.contains("EntityKnockbackEvent"),
                "nothing refuses a shove that is not damage - an explosion, a piston, a wind"
                        + " charge - so the figure can end up inside the tavern wall");
    }

    @Test
    void theGuardAsksByIdentity() {
        // Comments stripped: this file argues in prose about a check it must NOT make, which a search would agree with.
        final String guard = code(read(GUARD));

        assertEquals(
                3,
                count(guard, "npc.is("),
                "each of the three handlers has to ask SpawnNpc#is; a handler that does not is"
                        + " either protecting nothing or protecting everything");
        assertFalse(
                guard.contains("instanceof Mannequin"),
                "the guard recognises the NPC by type. That protects every mannequin on the"
                        + " server - a decoration in somebody's base included - and turns an"
                        + " ordinary entity into one nobody can ever remove");
    }

    @Test
    void theGuardIsWired() {
        final String plugin = (read(PLUGIN) + "\n" + read(START));

        assertTrue(
                plugin.contains("new NpcProtection(npc)"),
                "SmpPlugin never registers NpcProtection. The handlers are then perfect and never"
                        + " run, which looks exactly like having no guard at all");
        assertTrue(
                plugin.contains("registerEvents(new NpcProtection(npc)"),
                "NpcProtection is constructed but not handed to the plugin manager, so no event" + " ever reaches it");
    }

    @Test
    void thereIsOnlyOneWayToGetAFigure() {
        // The flags apply only inside pre-spawn; `spawn()` sweeps then creates, so nothing can adopt an old figure.
        final String figure = read(FIGURE);

        assertEquals(
                1,
                count(figure, "world.spawn("),
                "the figure is created in more than one place. Every one of them has to set"
                        + " immovable, invulnerable, silent and persistent, and the second one is"
                        + " where that is forgotten");
        assertTrue(
                figure.contains("spawned = figure.getUniqueId()"),
                "SpawnNpc no longer remembers what it spawned, so NpcProtection#npc.is answers"
                        + " false for the figure it is meant to defend and the guard is a no-op");
        for (final String flag :
                List.of("setImmovable(true)", "setInvulnerable(true)", "setSilent(true)", "setPersistent(true)")) {
            assertTrue(
                    figure.contains("mannequin." + flag),
                    "the figure is spawned without " + flag + ", and the guard is a second line of"
                            + " defence rather than the first");
        }
    }

    /**
     * The file with its comments taken out.
     *
     * Crude on purpose - it does not understand a {@code //} inside a string literal, and there is none in the two
     * files
     * this reads. Anything cleverer would be a Java parser, and the point of a text search is that it is smaller than
     * the thing it checks.
     */
    private static String code(final String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", " ");
    }

    private static int count(final String haystack, final String needle) {
        int found = 0;
        int at = haystack.indexOf(needle);
        while (at >= 0) {
            found++;
            at = haystack.indexOf(needle, at + needle.length());
        }
        return found;
    }

    /** Anchored on the directory holding {@code settings.gradle.kts}, the way every reader here is. */
    private static String read(final String relative) {
        try {
            Path candidate = Path.of("").toAbsolutePath();
            while (candidate != null && !Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) {
                candidate = candidate.getParent();
            }
            if (candidate == null) {
                throw new IllegalStateException("no settings.gradle.kts above the working directory");
            }
            final Path source = candidate.resolve(relative);
            assertTrue(Files.isRegularFile(source), relative + " no longer exists");
            return Files.readString(source, StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot read " + relative, e);
        }
    }
}
