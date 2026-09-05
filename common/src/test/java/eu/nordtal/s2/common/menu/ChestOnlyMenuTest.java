package eu.nordtal.s2.common.menu;

import eu.nordtal.s2.common.RepositoryRoot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two rules about menus, both of them enforced over the source text rather than over a running
 * server, because a server is the one place neither can be checked cheaply.
 *
 * <h2>Rule one: every menu is a chest</h2>
 * {@code docs/presentation.md} section 2 says so and, until 2026-09-04, said "a test asserts" it
 * while no such test existed. The rule earns its keep: all six chest heights are even
 * ({@code 114 + 18 * rows}), which is what makes one panel per row count line up. A hopper is 133 -
 * odd - so the moment a menu opens one, every panel in the pack is half a pixel out against it and
 * the arithmetic in {@link MenuTitle} quietly stops being true.
 *
 * <h2>Rule two: a menu's title comes through {@link MenuTitle}</h2>
 * A title composed by hand is a menu without a frame, and that failure is invisible in a diff -
 * the menu opens, it works, it is simply vanilla. The allowlist below is the remaining work made
 * visible in the build instead of in a document: each entry names a menu that has not been given
 * its panel yet, and emptying the list is what finishes that pass.
 *
 * <p>Same shape as {@code SoundVocabularyTest} and {@code OneMessageFormatTest}, and written the
 * same way: a rule with a named exception list beats a rule everybody remembers.</p>
 */
class ChestOnlyMenuTest {

    private static final List<String> MODULES =
            List.of("smp", "limbo", "hunger-games", "network-control");

    /**
     * Menus that compose their own title, and why they may.
     *
     * <p><b>Empty since 2026-09-04</b>, which is the whole point of it having existed. It carried
     * the four menus of the second pass - {@code ObjectiveGui}, {@code HandInGui},
     * {@code BalloonGui} and the grave - from the day the panel was built on {@code NavigateGui}
     * until the day they were all converted, and each line was deleted as its menu landed. The
     * remaining work was in the build rather than in a document, and the list emptying itself is
     * what said the pass was over.
     *
     * <p>It is kept rather than deleted because the next exception will want it, and an allowlist
     * that has to be reinvented is one that gets reinvented as a commented-out check. An entry here
     * is a debt: {@link #theAllowlistHasNoGhosts} is what collects it.</p>
     */
    private static final Map<String, String> UNFRAMED = new LinkedHashMap<>();

    /**
     * Helpers that compose a title <em>through</em> {@link MenuTitle} on a menu's behalf, keyed by
     * the call as it appears inside {@code createInventory(...)}, valued by the helper's source.
     *
     * <p>The balloon is the first menu whose surface is a panel plus overlays, and the composition
     * - which overlay on which card, at which x - is a fact about that menu and lives next to it in
     * {@code TravelPanel}. The rule below is unchanged: the helper's source has to reach
     * {@code MenuTitle.} itself, and {@link #everyComposerGoesThroughMenuTitle} checks that it does.
     */
    private static final Map<String, String> COMPOSERS = Map.of(
            "TravelPanel.title(", "smp/src/main/java/eu/nordtal/s2/smp/travel/TravelPanel.java");

    @Test
    @DisplayName("no menu opens anything but a chest")
    void everyMenuIsAChest() {
        final List<String> offenders = new ArrayList<>();
        forEachSource((path, source) -> {
            if (source.contains("InventoryType")) {
                offenders.add(path + " names InventoryType");
            }
        });
        assertEquals(List.of(), offenders,
                "a chest window is 114 + 18*rows pixels tall and all six sizes are even, which is"
                        + " the whole reason one panel per row count lines up. A hopper is 133 -"
                        + " odd - and every panel in the pack is out against it by half a slot.");
    }

    @Test
    @DisplayName("a menu's title is composed by MenuTitle, or it is on the list of the ones that are not")
    void everyTitleGoesThroughMenuTitle() {
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
        // The non-vacuity anchor. A rule of the shape "nothing in these four trees does X" passes
        // just as well when the walk finds no files at all, which is the one way this test could
        // go quietly useless - a moved source root, a renamed module. NavigateGui is pinned rather
        // than the whole census, so adding a menu correctly does not fail a test.
        assertTrue(framed.contains("smp/src/main/java/eu/nordtal/s2/smp/navigate/NavigateGui.java"),
                "NavigateGui is the reference implementation of the panel and has to be found by"
                        + " this scan; if it is not, the scan is finding nothing and the rule"
                        + " below is passing on an empty set. Found: " + framed);
        assertEquals(List.of(), offenders,
                "a menu whose title skips MenuTitle opens without a frame, and nothing about that"
                        + " fails - it is simply a vanilla window where a Nordtal one was meant."
                        + " If this menu is deliberately unframed, say so in UNFRAMED with the"
                        + " reason, the way the second menu pass did while it was running.");
    }

    @Test
    @DisplayName("every named composer really goes through MenuTitle")
    void everyComposerGoesThroughMenuTitle() {
        COMPOSERS.forEach((call, source) -> {
            final String text = read(RepositoryRoot.resolve(source));
            assertTrue(text.contains("MenuTitle."),
                    source + " is allowed to compose a title for a menu but never reaches"
                            + " MenuTitle itself - which makes the exception a hole in the rule");
        });
    }

    @Test
    @DisplayName("the allowlist has no ghosts")
    void theAllowlistHasNoGhosts() {
        final List<String> present = new ArrayList<>();
        forEachSource((path, source) -> {
            final String file = path.substring(path.lastIndexOf('/') + 1);
            if (UNFRAMED.containsKey(file) && source.contains("createInventory(")) {
                present.add(file);
            }
        });
        assertEquals(UNFRAMED.keySet(), new java.util.LinkedHashSet<>(present),
                "an allowlist entry for a menu that no longer opens an inventory - or that has"
                        + " since been framed - is an exception nobody is using, and it silently"
                        + " excuses the next file that happens to share the name. This is the check"
                        + " that failed as each menu of the second pass landed, which is how the"
                        + " list emptied itself.");
    }

    // --- helpers ---------------------------------------------------------------------------

    private static void forEachSource(final java.util.function.BiConsumer<String, String> consumer) {
        for (final String module : MODULES) {
            final Path root = RepositoryRoot.resolve(module + "/src/main");
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(path -> path.toString().endsWith(".java")).forEach(path ->
                        consumer.accept(RepositoryRoot.relative(path),
                                read(path)));
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
