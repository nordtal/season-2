package eu.nordtal.s2.common.message;

import eu.nordtal.s2.common.RepositoryRoot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * One message format across the whole network: MiniMessage, with {@code {named}} placeholders.
 *
 * <h2>What went wrong, and why prose was not enough to stop it</h2>
 * This repository ran three conventions at once until 2026-09-04, and nothing anywhere compared
 * them: {@code hunger-games}' bundles carried legacy section codes ({@code §l}), one
 * {@code network-control} key wrote MiniMessage tags, and the other five hundred lines were plain
 * text wrapped in {@code Component.text(...)}. Each of the three is invisible to the other two -
 * MiniMessage does not read a section code, and a section code is not a tag - so the way a mismatch
 * surfaces is a player reading {@code §l} or {@code <bold>} on their screen.
 *
 * <h2>The two rules this pins</h2>
 * <ol>
 *   <li>No message is wrapped in a bare {@code Component.text(messages...)} any more. That is the
 *       shape that renders a tag as literal text.</li>
 *   <li>No bundle carries a section code. That is the shape MiniMessage renders as literal text.</li>
 * </ol>
 *
 * <p>Both are checked against the source and the resources rather than against behaviour, because
 * behaviour needs a player: every one of these lines ends up on a disconnect screen, in a chat line
 * or on an item's lore, and none of that exists in a JVM with no server in it.</p>
 */
class OneMessageFormatTest {

    /** Every module that renders messages to a Minecraft client. */
    private static final List<String> MODULES =
            List.of("smp", "limbo", "hunger-games", "network-control");

    /**
     * Every file in the four modules that still calls {@code Component.text(...)}, and why.
     *
     * <p>This is an allowlist rather than a ban, because the boundary is genuinely uneven and
     * saying so is better than pretending otherwise. Three kinds of thing legitimately go through
     * {@code Component.text} and must keep doing so:</p>
     *
     * <ul>
     *   <li><b>Text that is not a message at all</b> - a glyph, a composed progress bar, a boss
     *       bar line, an entity's display name.</li>
     *   <li><b>Text that must not be parsed</b> - the updater's report, printed verbatim by module
     *       rule, and the MOTD's own already-parsed fallback.</li>
     *   <li><b>A message composed in Java with arbitrary text</b> - a GUI item name built from a
     *       message plus a POI name a player typed. Parsing that would let a POI called
     *       {@code <red>} colour the menu; the real fix is to pass the name as a parameter, which
     *       escapes it, and let the bundle carry the styling. That work is outstanding and is on
     *       the review's list, which is why these entries name a key rather than a reason.</li>
     * </ul>
     *
     * <p>Adding a file here is cheap and deliberate. Adding one <em>without</em> noticing is what
     * this list exists to prevent.</p>
     */
    private static final Map<String, String> COMPONENT_TEXT_ALLOWED = Map.ofEntries(
            Map.entry("smp/src/main/java/eu/nordtal/s2/smp/hud/SmpHud.java",
                    "the boss bar line, which is glyphs and needs a font key, not a parser"),
            Map.entry("hunger-games/src/main/java/eu/nordtal/s2/hungergames/hud/HudRenderer.java",
                    "the same boss bar line"),
            Map.entry("smp/src/main/java/eu/nordtal/s2/smp/player/PlayerComposition.java",
                    "glyphs and player names - the nametag, the tab entry and the chat prefix"),
            Map.entry("smp/src/main/java/eu/nordtal/s2/smp/npc/SpawnNpc.java",
                    "the NPC's name out of config.yml, which is a name and not a message"),
            Map.entry("hunger-games/src/main/java/eu/nordtal/s2/hungergames/body/PlayerBodies.java",
                    "a disconnected player's name on their body"),
            Map.entry("hunger-games/src/main/java/eu/nordtal/s2/hungergames/lobby/Lobby.java",
                    "one space between the broadcast and the clickable link"),
            Map.entry("smp/src/main/java/eu/nordtal/s2/smp/command/UpdateCommands.java",
                    "the updater's report, verbatim by module rule - see the comment there"),
            Map.entry("network-control/src/main/java/eu/nordtal/s2/networkcontrol/ping/NetworkPing.java",
                    "the MOTD, which NetworkPing parses itself with its own placeholder resolver"),
            Map.entry("smp/src/main/java/eu/nordtal/s2/smp/board/Boards.java",
                    "OUTSTANDING: board lines composed from a message plus a glyph progress bar"),
            Map.entry("smp/src/main/java/eu/nordtal/s2/smp/navigate/NavigateGui.java",
                    "OUTSTANDING: an item name that is a message or a player-typed POI name"),
            Map.entry("smp/src/main/java/eu/nordtal/s2/smp/npc/ObjectiveGui.java",
                    "OUTSTANDING: item names and lore composed in Java"),
            Map.entry("smp/src/main/java/eu/nordtal/s2/smp/travel/BalloonGui.java",
                    "OUTSTANDING: item lore composed in Java"));

    @Test
    @DisplayName("only the listed files compose components by hand")
    void onlyTheListedFilesUseComponentText() {
        final List<String> unlisted = new ArrayList<>();
        for (final String module : MODULES) {
            for (final Path source : sources(module)) {
                if (!read(source).contains("Component.text(")) {
                    continue;
                }
                final String relative = RepositoryRoot.path().relativize(source).toString();
                if (!COMPONENT_TEXT_ALLOWED.containsKey(relative)) {
                    unlisted.add(relative);
                }
            }
        }
        assertEquals(List.of(), unlisted,
                "a new Component.text(...) in a module that talks to players is either text that is"
                        + " not a message - in which case add it to COMPONENT_TEXT_ALLOWED with the"
                        + " reason - or a message that stopped going through MessageRenderer, which"
                        + " is the thing this whole class exists to catch");
    }

    @Test
    @DisplayName("every allowlisted file still exists")
    void theAllowlistHasNoGhosts() {
        final List<String> gone = COMPONENT_TEXT_ALLOWED.keySet().stream()
                .filter(relative -> !read(RepositoryRoot.resolve(relative)).contains("Component.text("))
                .sorted()
                .toList();
        assertEquals(List.of(), gone,
                "an entry here for a file that no longer composes anything by hand is an exception"
                        + " nobody is taking any more - delete it, so the list keeps meaning what it"
                        + " says");
    }

    @Test
    @DisplayName("no message is rendered with a bare Component.text")
    void nothingWrapsAMessageInComponentText() {
        final List<String> offenders = new ArrayList<>();
        for (final String module : MODULES) {
            for (final Path source : sources(module)) {
                final String text = read(source);
                if (text.contains("Component.text(messages.")) {
                    offenders.add(RepositoryRoot.path().relativize(source).toString());
                }
            }
        }
        assertEquals(List.of(), offenders,
                "Component.text(messages.get(...)) renders a MiniMessage tag as the literal text"
                        + " '<red>'. Use MessageRenderer.of(messages).get(...) instead - it is the"
                        + " one place that knows the format, and it escapes substituted values so a"
                        + " player called <red> cannot colour the rest of the line");
    }

    @Test
    @DisplayName("no bundle carries a legacy section code")
    void noBundleCarriesASectionCode() {
        final List<String> offenders = new ArrayList<>();
        for (final String module : MODULES) {
            for (final Path bundle : bundles(module)) {
                properties(bundle).forEach((key, value) -> {
                    if (String.valueOf(value).indexOf('§') >= 0) {
                        offenders.add(RepositoryRoot.path().relativize(bundle) + " " + key);
                    }
                });
            }
        }
        assertEquals(List.of(), offenders,
                "MiniMessage does not read section codes and never will - a value carrying one"
                        + " reaches the player with the code in it. Write the tag instead:"
                        + " §l is <bold>, §r is </bold> or the end of the component");
    }

    // --- helpers ---------------------------------------------------------------------------

    private static List<Path> sources(final String module) {
        return walk(module + "/src/main", ".java");
    }

    private static List<Path> bundles(final String module) {
        return walk(module + "/src/main/resources/messages", ".properties");
    }

    private static List<Path> walk(final String relative, final String suffix) {
        final Path root = RepositoryRoot.resolve(relative);
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(path -> path.toString().endsWith(suffix)).sorted().toList();
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot walk " + root, e);
        }
    }

    private static String read(final Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot read " + path, e);
        }
    }

    private static Properties properties(final Path path) {
        final Properties properties = new Properties();
        try (Reader reader = new InputStreamReader(Files.newInputStream(path), StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot read " + path, e);
        }
        return properties;
    }
}
