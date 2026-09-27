package eu.nordtal.s2.smp.grave;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * One grave is one window, however many people are standing in it.
 *
 * A window per viewer let two looters each take the lot; a text search, since that needs two clients.
 */
class OneGraveOneWindowTest {

    private static final String SOURCE = "smp/src/main/java/eu/nordtal/s2/smp/grave/Graves.java";

    @Test
    void theWindowIsShared() {
        final String source = read();

        assertTrue(
                source.contains("shown.computeIfAbsent(graveId"),
                "opening a grave builds a fresh inventory from the stored contents, so two people"
                        + " looting one grave each take the whole of it");

        // Counted, not measured by distance: the window's construction moved once already and could move again.
        assertEquals(
                1,
                count(source, "Bukkit.createInventory("),
                "a grave window is built in exactly one place. Two construction sites is how a"
                        + " second viewer gets a second inventory filled from the same stored"
                        + " contents, which is what paid out everything the dead player carried,"
                        + " twice - and nothing about it is visible in the game");

        // ...and that one place is what the lookup delegates to, so construction sits inside the reuse, not beside it.
        final int lookup = source.indexOf("shown.computeIfAbsent(graveId");
        final String tail = source.substring(lookup);
        final java.util.regex.Matcher call = java.util.regex.Pattern.compile(
                        "computeIfAbsent\\(graveId, \\w+ -> (\\w+)\\(")
                .matcher(tail);
        assertTrue(
                call.find(),
                "the lookup no longer delegates to a named builder: "
                        + tail.substring(0, Math.min(120, tail.length())));
        final int builder = source.indexOf("Inventory " + call.group(1) + "(");
        assertTrue(builder >= 0, "there is no method called " + call.group(1));
        assertTrue(
                source.indexOf("Bukkit.createInventory(") > builder,
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
    void theWriteBackWaitsForTheLastViewer() {
        final String source = read();

        assertTrue(
                source.contains("inventory.getViewers().isEmpty()"),
                "a grave is settled while somebody may still be taking things out of it, so the"
                        + " snapshot written back is already out of date when it is written");

        final int closed = source.indexOf("public void onClosed(");
        final int deferred = source.indexOf("runTask(plugin, () -> settle(", closed);
        assertTrue(
                deferred > closed && deferred - closed < 600,
                "Bukkit fires the close before it drops the viewer, so \"is anybody left?\" has no"
                        + " honest answer inside the event - the settle has to be a tick later");
    }

    @Test
    void thereIsOneMap() {
        final String source = read();

        assertFalse(
                source.contains("Map<Inventory, UUID>"),
                "a map from inventory to grave is a map with one entry per viewer, which is the"
                        + " shape that duplicated the loot in the first place");
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
