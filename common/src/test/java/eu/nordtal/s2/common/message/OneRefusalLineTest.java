package eu.nordtal.s2.common.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.common.RepositoryRoot;
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
import org.junit.jupiter.api.Test;

/**
 * Checks that "no such command" and "you may not type that" are one sentence everywhere.
 *
 * Two sentences would let a player enumerate the command tree. {@code CommandGate} and
 * {@code CommandFilter}, including its {@link org.bukkit.event.command.UnknownCommandEvent} handler,
 * must all render the same message; a text search, as the handlers need a running server.
 */
class OneRefusalLineTest {

    /** The one key. It lives in {@code :commands}' bundle, so both surfaces already share it. */
    private static final String KEY = "command.unknown";

    /** The two classes that answer a command somebody may not, or cannot, run. */
    private static final List<String> REFUSERS = List.of(
            "paper-common/src/main/java/eu/nordtal/s2/papercommon/command/CommandFilter.java",
            "proxy/src/main/java/eu/nordtal/s2/proxy/command/CommandGate.java");

    /** Any message a refuser renders: a chain of section and key calls on the spec, named in kebab case. */
    private static final Pattern RENDERED_KEY = Pattern.compile("MESSAGES((?:\\s*\\.\\s*[a-zA-Z0-9]+\\(\\))+)");

    @Test
    void bothRefusersSayExactlyOneThingAndItIsTheSameThing() {
        final Set<String> keys = new TreeSet<>();
        for (final String refuser : REFUSERS) {
            final Matcher matcher = RENDERED_KEY.matcher(read(RepositoryRoot.resolve(refuser)));
            while (matcher.find()) {
                keys.add(keyOf(matcher.group(1)));
            }
        }
        assertEquals(
                Set.of(KEY),
                keys,
                "a second sentence here is how a player learns which of 'you may not' and 'there is"
                        + " no such command' they hit - which is the whole of what the allowlist is"
                        + " keeping from them");
    }

    @Test
    void thePaperSideAnswersUnknowncommandeventOrAnAdminReadsVanillas() {
        final String filter = read(RepositoryRoot.resolve(REFUSERS.get(0)));
        assertTrue(
                filter.contains("UnknownCommandEvent"),
                "without this handler a typo, an admin's mistyped command and every command typed"
                        + " before a proxy has published a list all read vanilla's 'Unknown or"
                        + " incomplete command', in the server's language, with a red caret");
    }

    /** Reads the bundle off the file, since {@code :commands} is on no classpath a test here can load. */
    @Test
    void theOneKeyExistsInBothLanguagesOfTheSharedBundle() {
        for (final String language : List.of("en", "de")) {
            final Path bundle =
                    RepositoryRoot.resolve("commands/src/main/resources/messages/commands/" + language + ".properties");
            assertTrue(
                    read(bundle).contains("\n" + KEY + "="),
                    KEY + " is missing from " + language + ", so the refusal reaches a player as the"
                            + " key itself - which does tell them something, in the worst way");
        }
    }

    /** Nothing else in the repository may declare a second key that means the same. */
    @Test
    void noBundleDeclaresASecondRefusalKey() {
        final List<String> suspects = new ArrayList<>();
        for (final Path bundle : bundles()) {
            for (final String line : read(bundle).split("\n", -1)) {
                final String key = line.split("=", 2)[0].strip();
                if (key.equals(KEY) || key.startsWith("#")) {
                    continue;
                }
                if (key.endsWith(".unknown-command")
                        || key.endsWith(".no-such-command")
                        || key.endsWith("command.not-allowed")
                        || key.endsWith("command.refused")) {
                    suspects.add(RepositoryRoot.relative(bundle) + ": " + key);
                }
            }
        }
        assertEquals(
                List.of(),
                suspects,
                "a key with this shape is a second way of saying " + KEY + ". If one is genuinely"
                        + " needed, that is a decision to take out loud - the allowlist's wording"
                        + " rests on there being one sentence");
    }

    /** {@code .command().noSuchThing()} to {@code command.no-such-thing}. */
    private static String keyOf(final String chain) {
        final List<String> segments = new ArrayList<>();
        for (final String call : chain.replaceAll("\\s", "").split("\\(\\)", -1)) {
            if (!call.isEmpty()) {
                segments.add(call.substring(1)
                        .replaceAll("([a-z0-9])([A-Z])", "$1-$2")
                        .toLowerCase(java.util.Locale.ROOT));
            }
        }
        return String.join(".", segments);
    }

    private static List<Path> bundles() {
        final List<Path> found = new ArrayList<>();
        for (final String root :
                List.of("smp", "limbo", "hunger-games", "proxy", "commands", "paper-common", "discord-bot")) {
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
        assertTrue(
                Files.isRegularFile(source),
                source + " is missing, and a missing file is a check that silently stops running");
        try {
            return Files.readString(source, StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot read " + source, e);
        }
    }
}
