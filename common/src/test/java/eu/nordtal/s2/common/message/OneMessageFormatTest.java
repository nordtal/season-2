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
 * One message format across the whole network: MiniMessage, with {@code {named}} placeholders. A
 * mismatch is invisible until a player reads {@code §l} or {@code <bold>} on their screen.
 *
 * <p>Two rules: no message is wrapped in a bare {@code Component.text(messages...)}, which renders a
 * tag as literal text, and no bundle carries a section code, which MiniMessage renders as literal
 * text. Both are checked against the source and the resources, because behaviour needs a player.
 */
class OneMessageFormatTest {

    /** Every module that renders messages to a Minecraft client. */
    private static final List<String> MODULES =
            List.of("smp", "limbo", "hunger-games", "network-control");

    /**
     * Every file in the four modules that still calls {@code Component.text(...)}, and why. Three
     * kinds of thing legitimately do: text that is not a message (a glyph, an entity's display
     * name), text that must not be parsed (the updater's report), and a message composed in Java
     * around arbitrary player-supplied text.
     *
     * <p>Adding a file here is cheap and deliberate; adding one <em>without</em> noticing is what
     * this list exists to prevent.
     */
    private static final Map<String, String> COMPONENT_TEXT_ALLOWED = Map.ofEntries(
            Map.entry("smp/src/main/java/eu/nordtal/s2/smp/player/PlayerComposition.java",
                    "glyphs and player names - the nametag, the tab entry and the chat prefix"),
            Map.entry("smp/src/main/java/eu/nordtal/s2/smp/npc/SpawnNpc.java",
                    "the NPC's name out of config.yml, which is a name and not a message"),
            Map.entry("smp/src/main/java/eu/nordtal/s2/smp/welcome/SeasonWelcome.java",
                    "the frames of the season's opening moment, which are pictures rather than"
                            + " sentences - a glyph may never be written into a .properties file."
                            + " Its subtitle, which IS language, goes through MessageRenderer"),
            Map.entry("hunger-games/src/main/java/eu/nordtal/s2/hungergames/body/PlayerBodies.java",
                    "a disconnected player's name on their body"),
            Map.entry("hunger-games/src/main/java/eu/nordtal/s2/hungergames/player/ArenaComposition.java",
                    "a flag glyph and a player name - this server's half of the shared system"
                            + " lines, the same exemption smp's PlayerComposition has"),
            Map.entry("network-control/src/main/java/eu/nordtal/s2/networkcontrol/ping/NetworkPing.java",
                    "the MOTD, which NetworkPing parses itself with its own placeholder resolver"),
            Map.entry("network-control/src/main/java/eu/nordtal/s2/networkcontrol/command/VelocityUser.java",
                    "NordtalUser#replyLiteral - text that IS already the answer and must not be"
                            + " rendered twice"),
            Map.entry("network-control/src/main/java/eu/nordtal/s2/networkcontrol/command/ProxyChatEffects.java",
                    "a private message's own text, wrapped so that it can be handed to"
                            + " MessageRenderer's COMPONENT slot - which is what keeps a player"
                            + " called <red> from colouring somebody else's chat. The line around"
                            + " it is a bundle key like any other"),
            Map.entry("network-control/src/main/java/eu/nordtal/s2/networkcontrol/command/ConsoleUser.java",
                    "the same NordtalUser#replyLiteral, for the proxy console - plus a plain-text"
                            + " serialiser, because a raw <green> in a container log is a thing"
                            + " somebody greps past"));

    @Test
    @DisplayName("only the listed files compose components by hand")
    void onlyTheListedFilesUseComponentText() {
        final List<String> unlisted = new ArrayList<>();
        for (final String module : MODULES) {
            for (final Path source : sources(module)) {
                if (!read(source).contains("Component.text(")) {
                    continue;
                }
                final String relative = RepositoryRoot.relative(source);
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
                    offenders.add(RepositoryRoot.relative(source));
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
                        offenders.add(RepositoryRoot.relative(bundle) + " " + key);
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
