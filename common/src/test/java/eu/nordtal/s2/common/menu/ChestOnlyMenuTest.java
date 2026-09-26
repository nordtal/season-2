package eu.nordtal.s2.common.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.common.RepositoryRoot;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Checks over the source that every menu is a chest and gets its title through {@link MenuTitle}.
 *
 * Chest heights are even, which the panel arithmetic needs; a hand-made title is a menu without a frame.
 */
class ChestOnlyMenuTest {

    private static final List<String> MODULES = List.of("smp", "limbo", "hunger-games", "proxy");

    /** Menus that compose their own title, with the reason; empty, and checked by {@link #theAllowlistHasNoGhosts}. */
    private static final Map<String, String> UNFRAMED = new LinkedHashMap<>();

    /**
     * Helpers that compose a title through {@link MenuTitle} for a menu, keyed by the call, valued by the source.
     *
     * {@link #everyNamedComposerReallyGoesThroughMenutitle} checks that each helper reaches {@code MenuTitle.}.
     */
    private static final Map<String, String> COMPOSERS = Map.ofEntries(
            Map.entry("TravelPanel.title(", "smp/src/main/java/eu/nordtal/s2/smp/travel/TravelPanel.java"),
            Map.entry("NavigatePanel.title(", "smp/src/main/java/eu/nordtal/s2/smp/navigate/NavigatePanel.java"),
            Map.entry("ObjectivePanel.title(", "smp/src/main/java/eu/nordtal/s2/smp/npc/ObjectivePanel.java"),
            Map.entry("HandInPanel.title(", "smp/src/main/java/eu/nordtal/s2/smp/npc/HandInPanel.java"),
            Map.entry("GravePanel.title(", "smp/src/main/java/eu/nordtal/s2/smp/grave/GravePanel.java"),
            Map.entry("WheelPanel.title(", "smp/src/main/java/eu/nordtal/s2/smp/wheel/WheelPanel.java"));

    @Test
    void noMenuOpensAnythingButAChest() {
        final List<String> offenders = new ArrayList<>();
        forEachSource((path, source) -> {
            if (source.contains("InventoryType")) {
                offenders.add(path + " names InventoryType");
            }
        });
        assertEquals(
                List.of(),
                offenders,
                "a chest window is 114 + 18*rows pixels tall and all six sizes are even, which is"
                        + " the whole reason one panel per row count lines up. A hopper is 133 -"
                        + " odd - and every panel in the pack is out against it by half a slot.");
    }

    @Test
    void aMenusTitleIsComposedByMenutitleOrItIsOnTheListOfTheOnesThatAreNot() {
        final List<String> offenders = new ArrayList<>();
        final List<String> framed = new ArrayList<>();
        forEachSource((path, source) -> {
            final String file = path.substring(path.lastIndexOf('/') + 1);
            int at = source.indexOf("createInventory(");
            while (at >= 0) {
                final String call = source.substring(at, endOfCall(source, at));
                if (call.contains("MenuTitle.") || COMPOSERS.keySet().stream().anyMatch(call::contains)) {
                    framed.add(path);
                } else if (!UNFRAMED.containsKey(file)) {
                    offenders.add(path + " composes an inventory title without MenuTitle");
                }
                at = source.indexOf("createInventory(", at + 1);
            }
        });
        // Non-vacuity anchor: the rule would also pass if the walk found no files.
        assertTrue(
                framed.contains("smp/src/main/java/eu/nordtal/s2/smp/navigate/NavigateGui.java"),
                "NavigateGui is the reference implementation of the panel and has to be found by"
                        + " this scan; if it is not, the scan is finding nothing and the rule"
                        + " below is passing on an empty set. Found: " + framed);
        assertEquals(
                List.of(),
                offenders,
                "a menu whose title skips MenuTitle opens without a frame, and nothing about that"
                        + " fails - it is simply a vanilla window where a Nordtal one was meant."
                        + " If this menu is deliberately unframed, say so in UNFRAMED with the"
                        + " reason.");
    }

    @Test
    void everyNamedComposerReallyGoesThroughMenutitle() {
        COMPOSERS.forEach((call, source) -> {
            final String text = read(RepositoryRoot.resolve(source));
            assertTrue(
                    text.contains("MenuTitle."),
                    source + " is allowed to compose a title for a menu but never reaches"
                            + " MenuTitle itself - which makes the exception a hole in the rule");
        });
    }

    @Test
    void theAllowlistHasNoGhosts() {
        final List<String> present = new ArrayList<>();
        forEachSource((path, source) -> {
            final String file = path.substring(path.lastIndexOf('/') + 1);
            if (UNFRAMED.containsKey(file) && source.contains("createInventory(")) {
                present.add(file);
            }
        });
        assertEquals(
                UNFRAMED.keySet(),
                new java.util.LinkedHashSet<>(present),
                "an allowlist entry for a menu that does not open an inventory - or one that has been"
                        + " framed instead - is an exception nobody is using, and it silently"
                        + " excuses the next file that happens to share the name.");
    }

    private static void forEachSource(final java.util.function.BiConsumer<String, String> consumer) {
        for (final String module : MODULES) {
            final Path root = RepositoryRoot.resolve(module + "/src/main");
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(path -> path.toString().endsWith(".java"))
                        .forEach(path -> consumer.accept(RepositoryRoot.relative(path), read(path)));
            } catch (final IOException e) {
                throw new UncheckedIOException("cannot walk " + root, e);
            }
        }
    }

    /** The end of a {@code createInventory(...)} call, by counting brackets from its own. */
    private static int endOfCall(final String source, final int start) {
        int depth = 0;
        for (int index = source.indexOf('(', start); index < source.length(); index++) {
            final char character = source.charAt(index);
            if (character == '(') {
                depth++;
            } else if (character == ')') {
                depth--;
                if (depth == 0) {
                    return index + 1;
                }
            }
        }
        return source.length();
    }

    private static String read(final Path path) {
        try {
            return Files.readString(path);
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot read " + path, e);
        }
    }
}
