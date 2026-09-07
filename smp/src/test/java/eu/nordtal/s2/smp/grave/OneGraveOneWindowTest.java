package eu.nordtal.s2.smp.grave;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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

        final int lookup = source.indexOf("shown.computeIfAbsent(graveId");
        final int built = source.indexOf("Bukkit.createInventory(", lookup);
        assertTrue(built > lookup && built - lookup < 800,
                "the only place a grave inventory is created has to sit inside the lookup that"
                        + " reuses an existing one");
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
