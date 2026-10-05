package eu.nordtal.season.messages;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.RepositoryRoot;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Checks that "no such command" and "you may not type that" are one sentence everywhere.
 *
 * Two sentences would let a player enumerate the command tree. That both refusers render only this key is
 * {@code :architecture}'s rule; the bundles that word it are checked here.
 */
class OneRefusalLineTest {

    /** The one key, in paper-common's bundle and in the proxy's, which share no bundle. */
    private static final String KEY = "command.unknown";

    /** The two bundles carrying it, each missing its language and extension. */
    private static final List<String> BUNDLES = List.of(
            "paper-common/src/main/resources/messages/paper-common/", "proxy/src/main/resources/messages/proxy/");

    /** Reads both bundles off the file, since neither module is on a classpath a test here can load. */
    @Test
    void theOneKeySaysTheSameInPaperAndInTheProxy() throws java.io.IOException {
        for (final String language : List.of("en", "de")) {
            final java.util.Set<String> sentences = new TreeSet<>();
            for (final String bundle : BUNDLES) {
                final java.util.Properties read = new java.util.Properties();
                try (java.io.Reader in = java.nio.file.Files.newBufferedReader(
                        RepositoryRoot.resolve(bundle + language + ".properties"))) {
                    read.load(in);
                }
                final String sentence = read.getProperty(KEY);
                assertTrue(
                        sentence != null,
                        KEY + " is missing from " + bundle + language + ", so the refusal reaches a player as the"
                                + " key itself - which does tell them something, in the worst way");
                sentences.add(sentence);
            }
            assertEquals(1, sentences.size(), "the proxy and Paper word " + KEY + " differently: " + sentences);
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

    private static List<Path> bundles() {
        final List<Path> found = new ArrayList<>();
        for (final String root : List.of("smp", "limbo", "hunger-games", "proxy", "paper-common", "discord-bot")) {
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
