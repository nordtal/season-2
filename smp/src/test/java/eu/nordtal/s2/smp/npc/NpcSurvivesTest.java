package eu.nordtal.s2.smp.npc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The figure in the tavern survives being hit, and the guard that makes that true is switched on.
 *
 * <h2>The failure it exists for</h2>
 * {@code SpawnNpc} has set {@code setInvulnerable(true)} since the day it was written, and that flag
 * is not what it reads as: vanilla lets a <b>creative-mode</b> player hit straight through it, and
 * the <b>void</b> ignores it outright. Every admin standing next to the NPC is in creative mode. The
 * spawn protection is a rule about blocks, so it defends nothing here. The figure is the only way a
 * {@code HAND_IN} objective can be fulfilled, so the whole milestone track goes with it and nothing
 * says so - the next player to right-click simply finds nothing to click (finding 150).
 *
 * <h2>Why the registration is half the test</h2>
 * A test that read {@code NpcProtection} alone would have been green on the defect this is written
 * for: the handlers can be perfect and never run. The listener is one line in {@code SmpPlugin}, in
 * a method with twenty of them, and it is exactly the line a merge drops. {@code AdminWatchWiringTest}
 * in {@code :common} exists for the same shape of failure - a mechanism that was written, was
 * tested, and had no caller for a day.
 *
 * <h2>Why a text search</h2>
 * Raising an {@code EntityDamageEvent} needs a world, an entity and a damage source, and spawning a
 * {@code Mannequin} needs a server. What is being protected is a registration and three cancels,
 * which is the same reason {@code PortalGateWiringTest} and {@code SpawnNpcLabelTest} are text
 * searches.
 */
class NpcSurvivesTest {

    private static final String GUARD = "smp/src/main/java/eu/nordtal/s2/smp/npc/NpcProtection.java";
    private static final String FIGURE = "smp/src/main/java/eu/nordtal/s2/smp/npc/SpawnNpc.java";
    private static final String PLUGIN = "smp/src/main/java/eu/nordtal/s2/smp/SmpPlugin.java";

    @Test
    @DisplayName("a hit, a fire and a shove aimed at the NPC are all refused")
    void theGuardRefusesTheThreeWaysToLoseIt() {
        final String guard = read(GUARD);

        assertTrue(guard.contains("EntityDamageEvent"),
                "nothing cancels damage to the NPC, so a creative-mode hit or a step into the void"
                        + " removes the only entity a HAND_IN objective can be handed to");
        assertTrue(guard.contains("EntityCombustEvent"),
                "the NPC can still catch fire. With damage cancelled it would burn for the rest of"
                        + " the season without ever dying, which reads as broken to everybody who"
                        + " walks past the tavern");
        assertTrue(guard.contains("EntityKnockbackEvent"),
                "nothing refuses a shove that is not damage - an explosion, a piston, a wind"
                        + " charge - so the figure can end up inside the tavern wall");
    }

    @Test
    @DisplayName("the guard defends this one figure and not every mannequin on the server")
    void theGuardAsksByIdentity() {
        // Comments stripped, because this file argues in prose about the check it must NOT make -
        // and a search that reads the argument as the code is a search that can only ever agree
        // with whatever is written next to it.
        final String guard = code(read(GUARD));

        assertEquals(3, count(guard, "npc.is("),
                "each of the three handlers has to ask SpawnNpc#is; a handler that does not is"
                        + " either protecting nothing or protecting everything");
        assertFalse(guard.contains("instanceof Mannequin"),
                "the guard recognises the NPC by type. That protects every mannequin on the"
                        + " server - a decoration in somebody's base included - and turns an"
                        + " ordinary entity into one nobody can ever remove");
    }

    @Test
    @DisplayName("the guard is actually registered")
    void theGuardIsWired() {
        final String plugin = read(PLUGIN);

        assertTrue(plugin.contains("new NpcProtection(npc)"),
                "SmpPlugin never registers NpcProtection. The handlers are then perfect and never"
                        + " run, which looks exactly like having no guard at all");
        assertTrue(plugin.contains("registerEvents(new NpcProtection(npc)"),
                "NpcProtection is constructed but not handed to the plugin manager, so no event"
                        + " ever reaches it");
    }

    @Test
    @DisplayName("every path that produces the figure produces one carrying the flags")
    void thereIsOnlyOneWayToGetAFigure() {
        // The guard above is the answer to a creative-mode hit; the flags are still the answer to
        // everything ordinary, and they are only ever applied inside the pre-spawn function. So the
        // question this asserts is not "are the flags set" - SpawnNpcLabelTest reads that file too -
        // but "is there a second way to end up holding a figure that never went through it".
        //
        // There is not, and that is what has to stay true: `spawn()` sweeps and then creates, and
        // the figure is persistent since 2026-09-06, so a path that ADOPTED the surviving entity
        // instead of replacing it would hold a mannequin from an older start with whatever flags
        // that start gave it. Findings 100 and 106 are both about this spot.
        final String figure = read(FIGURE);

        assertEquals(1, count(figure, "world.spawn("),
                "the figure is created in more than one place. Every one of them has to set"
                        + " immovable, invulnerable, silent and persistent, and the second one is"
                        + " where that is forgotten");
        assertTrue(figure.contains("spawned = figure.getUniqueId()"),
                "SpawnNpc no longer remembers what it spawned, so NpcProtection#npc.is answers"
                        + " false for the figure it is meant to defend and the guard is a no-op");
        for (final String flag : List.of("setImmovable(true)", "setInvulnerable(true)",
                "setSilent(true)", "setPersistent(true)")) {
            assertTrue(figure.contains("mannequin." + flag),
                    "the figure is spawned without " + flag + ", and the guard is a second line of"
                            + " defence rather than the first");
        }
    }

    /**
     * The file with its comments taken out.
     *
     * <p>Crude on purpose - it does not understand a {@code //} inside a string literal, and there
     * is none in the two files this reads. Anything cleverer would be a Java parser, and the point
     * of a text search is that it is smaller than the thing it checks.
     */
    private static String code(final String source) {
        return source
                .replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("(?m)//.*$", " ");
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
            while (candidate != null
                    && !Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) {
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
