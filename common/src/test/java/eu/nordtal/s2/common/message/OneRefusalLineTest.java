package eu.nordtal.s2.common.message;

import eu.nordtal.s2.common.RepositoryRoot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One sentence for "that command does not exist" and for "you may not type that", everywhere.
 *
 * <h2>Why it has to be one</h2>
 * The command allowlist's whole design is that being refused teaches a player nothing about what
 * exists. Two sentences break that immediately: whichever one somebody gets tells them which of the
 * two cases they are in, and a player who can tell "refused" from "no such thing" can enumerate the
 * command tree by typing at it. Nothing about the two lines being different would ever look wrong -
 * both are correct, both are translated, and a player sees one at a time.
 *
 * <h2>The four places that say it</h2>
 * A refusal is produced twice on the proxy and twice on every backend, and the four arrived at
 * different times: {@code CommandGate} refuses a command the list does not carry and answers a root
 * Velocity does not know; {@code CommandFilter} does the same on Paper, plus - since 2026-09-09 -
 * {@link org.bukkit.event.command.UnknownCommandEvent}, which is what an <b>admin</b> and what
 * <b>everybody on a network with no published list</b> hit, and which without a handler is vanilla's
 * "Unknown or incomplete command, see below for error" with a red caret under the offending
 * character, in the server's language.
 *
 * <p>So this walks the two files and asserts that every message key either of them names for a
 * refusal is the same one. It is a text search because there is nothing else it could be: both
 * handlers are fired by a platform, on a running server, at a player.</p>
 */
class OneRefusalLineTest {

    /** The one key. It lives in {@code :commands}' bundle, so both surfaces already share it. */
    private static final String KEY = "command.unknown";

    /** The two classes that answer a command somebody may not, or cannot, run. */
    private static final List<String> REFUSERS = List.of(
            "paper-common/src/main/java/eu/nordtal/s2/papercommon/command/CommandFilter.java",
            "network-control/src/main/java/eu/nordtal/s2/networkcontrol/command/CommandGate.java");

    /** Any {@code "some.message.key"} handed to a renderer in those files. */
    private static final Pattern RENDERED_KEY =
            Pattern.compile("\\.get\\([^,]+,\\s*\"([a-z][a-z0-9.-]*)\"\\)");

    @Test
    @DisplayName("both refusers say exactly one thing, and it is the same thing")
    void thereIsOneRefusalLine() {
        final Set<String> keys = new TreeSet<>();
        for (final String refuser : REFUSERS) {
            final Matcher matcher = RENDERED_KEY.matcher(read(RepositoryRoot.resolve(refuser)));
            while (matcher.find()) {
                keys.add(matcher.group(1));
            }
        }
        assertEquals(Set.of(KEY), keys,
                "a second sentence here is how a player learns which of 'you may not' and 'there is"
                        + " no such command' they hit - which is the whole of what the allowlist is"
                        + " keeping from them");
    }

    @Test
    @DisplayName("the Paper side answers UnknownCommandEvent, or an admin reads vanilla's")
    void unknownCommandsAreAnsweredOnPaperToo() {
        final String filter = read(RepositoryRoot.resolve(REFUSERS.get(0)));
        assertTrue(filter.contains("UnknownCommandEvent"),
                "without this handler a typo, an admin's mistyped command and every command typed"
                        + " before a proxy has published a list all read vanilla's 'Unknown or"
                        + " incomplete command', in the server's language, with a red caret");
    }

    /**
     * Read off the file rather than through {@link Messages}: {@code :commands} depends on this
     * module and not the other way round, so its bundle is on no classpath a test here can load.
     */
    @Test
    @DisplayName("the one key exists in both languages of the shared bundle")
    void theKeyIsTranslated() {
        for (final String language : List.of("en", "de")) {
            final Path bundle = RepositoryRoot.resolve(
                    "commands/src/main/resources/messages/commands/" + language + ".properties");
            assertTrue(read(bundle).contains("\n" + KEY + "="),
                    KEY + " is missing from " + language + ", so the refusal reaches a player as the"
                            + " key itself - which does tell them something, in the worst way");
        }
    }

    /** Nothing else in the repository may declare a second key that means the same. */
    @Test
    @DisplayName("no bundle declares a second refusal key")
    void nobodyHasWrittenASecondOne() {
        final List<String> suspects = new ArrayList<>();
        for (final Path bundle : bundles()) {
            for (final String line : read(bundle).split("\n")) {
                final String key = line.split("=", 2)[0].strip();
                if (key.equals(KEY) || key.startsWith("#")) {
                    continue;
                }
                if (key.endsWith(".unknown-command") || key.endsWith(".no-such-command")
                        || key.endsWith("command.not-allowed") || key.endsWith("command.refused")) {
                    suspects.add(RepositoryRoot.relative(bundle) + ": " + key);
                }
            }
        }
        assertEquals(List.of(), suspects,
                "a key with this shape is a second way of saying " + KEY + ". If one is genuinely"
                        + " needed, that is a decision to take out loud - the allowlist's wording"
                        + " rests on there being one sentence");
    }

    private static List<Path> bundles() {
        final List<Path> found = new ArrayList<>();
        for (final String root : List.of("smp", "limbo", "hunger-games", "network-control",
                "commands", "paper-common", "discord-bot")) {
            final Path directory = RepositoryRoot.resolve(root + "/src/main/resources/messages");
            if (!Files.isDirectory(directory)) {
                continue;
            }
            try (Stream<Path> tree = Files.walk(directory)) {
                tree.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".properties"))
                        .sorted()
                        .forEach(found::add);
            } catch (final IOException e) {
                throw new UncheckedIOException("cannot walk " + directory, e);
            }
        }
        assertTrue(!found.isEmpty(), "no bundles found - the roots have moved");
        return found;
    }

    private static String read(final Path source) {
        assertTrue(Files.isRegularFile(source), source + " no longer exists - a missing file is a"
                + " check that silently stops running");
        try {
            return Files.readString(source, StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot read " + source, e);
        }
    }
}
