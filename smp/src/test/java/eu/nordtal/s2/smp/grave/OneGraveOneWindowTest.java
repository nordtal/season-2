package eu.nordtal.s2.smp.grave;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One grave is one window, however many people are standing in it.
 *
 * <h2>The failure it exists for</h2>
 * A grave is open to anybody - that is a decision and it is written into {@code Graves}' class
 * comment - so two people can right-click the same one in the same second. While each viewer got an
 * inventory of their own, both were filled from the same stored contents and each close serialised
 * its <em>own</em> whole snapshot back: both looters took the lot and the grave paid out everything
 * the dead player was carrying, twice. Nothing about that is visible in the game; both windows look
 * exactly like a grave being emptied (CodeRabbit, PR #8).
 *
 * <p>One shared inventory needs no rule of its own, because it is what a vanilla chest does: both
 * looters watch the same slots empty, and taking an item is a main-thread click on one object.</p>
 *
 * <h2>Why a text search</h2>
 * Two viewers on one inventory is a server, two clients and a corpse. What this protects is the
 * shape - a window looked up by grave id rather than built per viewer, and a write-back that waits
 * until the last person has left it.
 */
class OneGraveOneWindowTest {

    private static final String SOURCE = "smp/src/main/java/eu/nordtal/s2/smp/grave/Graves.java";

    @Test
    @DisplayName("the window is looked up by grave, not built for each viewer")
    void theWindowIsShared() {
        final String source = read();

        assertTrue(source.contains("shown.computeIfAbsent(graveId"),
                "opening a grave builds a fresh inventory from the stored contents, so two people"
                        + " looting one grave each take the whole of it");

        // Counted rather than measured against the lookup's own offset. This was a distance check
        // until 2026-09-09 - "the createInventory is within 800 characters of the computeIfAbsent" -
        // and it broke the moment the window's construction moved into a method of its own, which
        // it had to when the grave gained its painted footer. The distance was never the property;
        // ONE construction site is, and a second one anywhere in this file is the shape that
        // duplicated the loot however close it sits.
        assertEquals(1, count(source, "Bukkit.createInventory("),
                "a grave window is built in exactly one place. Two construction sites is how a"
                        + " second viewer gets a second inventory filled from the same stored"
                        + " contents, which is what paid out everything the dead player carried,"
                        + " twice - and nothing about it is visible in the game");

        // ...and that one place is what the lookup delegates to, so the construction is inside the
        // reuse rather than beside it.
        final int lookup = source.indexOf("shown.computeIfAbsent(graveId");
        final String tail = source.substring(lookup);
        final java.util.regex.Matcher call = java.util.regex.Pattern
                .compile("computeIfAbsent\\(graveId, \\w+ -> (\\w+)\\(").matcher(tail);
        assertTrue(call.find(), "the lookup no longer delegates to a named builder: "
                + tail.substring(0, Math.min(120, tail.length())));
        final int builder = source.indexOf("Inventory " + call.group(1) + "(");
        assertTrue(builder >= 0, "there is no method called " + call.group(1));
        assertTrue(source.indexOf("Bukkit.createInventory(") > builder,
                "the one construction site is not inside " + call.group(1) + ", so a window can be"
                        + " built without going through the lookup that reuses an existing one");
    }

    private static int count(final String source, final String needle) {
        int found = 0;
        int at = source.indexOf(needle);
        while (at >= 0) {
            found++;
            at = source.indexOf(needle, at + 1);
        }
        return found;
    }

    @Test
    @DisplayName("the contents are written back only once the last viewer has gone")
    void theWriteBackWaitsForTheLastViewer() {
        final String source = read();

        assertTrue(source.contains("inventory.getViewers().isEmpty()"),
                "a grave is settled while somebody may still be taking things out of it, so the"
                        + " snapshot written back is already out of date when it is written");

        final int closed = source.indexOf("public void onClosed(");
        final int deferred = source.indexOf("runTask(plugin, () -> settle(", closed);
        assertTrue(deferred > closed && deferred - closed < 600,
                "Bukkit fires the close before it drops the viewer, so \"is anybody left?\" has no"
                        + " honest answer inside the event - the settle has to be a tick later");
    }

    @Test
    @DisplayName("no second map keyed by inventory survives")
    void thereIsOneMap() {
        final String source = read();

        assertFalse(source.contains("Map<Inventory, UUID>"),
                "a map from inventory to grave is a map with one entry per viewer, which is the"
                        + " shape that duplicated the loot in the first place");
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
