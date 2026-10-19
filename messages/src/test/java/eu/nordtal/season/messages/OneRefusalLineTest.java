package eu.nordtal.season.messages;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.RepositoryRoot;
import eu.nordtal.season.messages.document.Document;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * "No such command" and "you may not type that" are one sentence everywhere.
 *
 * Two sentences would let a player list the command tree. {@code :architecture} checks that both
 * refusers render only their document {@code command-unknown}. This test checks that the two say the same
 * and that no bundle words the refusal a second time.
 */
class OneRefusalLineTest {

    private static final String KEY = "command.unknown";

    /** The two refusals are documents, read from the file system, as neither module is on this classpath. */
    private static final String PROXY = "proxy/src/main/resources/documents/proxy/command-unknown.json";

    private static final String PAPER = "paper-common/src/main/resources/documents/paper-common/command-unknown.json";

    @Test
    void theRefusalSaysTheSameInPaperAndInTheProxy() throws IOException {
        final Document paper = Document.decode(Files.readString(RepositoryRoot.resolve(PAPER)));
        final Document proxy = Document.decode(Files.readString(RepositoryRoot.resolve(PROXY)));
        assertEquals(paper, proxy, "the proxy and Paper word the refusal differently");
    }

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
        for (final String root : List.of("smp", "limbo", "hunger-games", "discord-bot")) {
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
