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
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Checks that every reply a command sends names a {@link Tone}, apart from the files named here.
 *
 * A text search, since the two-argument {@code reply} must stay; Discord ignores a tone, so it is not walked.
 */
class ReplyToneTest {

    /** Every source tree that can send a reply through {@code NordtalUser}. */
    private static final List<String> SOURCE_ROOTS = List.of(
            "commands/src/main/java",
            "paper-common/src/main/java",
            "proxy/src/main/java",
            "hunger-games/src/main/java",
            "smp/src/main/java",
            "limbo/src/main/java");

    /** The files that may send a reply without naming a tone, where naming one would be wrong. */
    private static final Map<String, String> ALLOWED = Map.of(
            "commands/src/main/java/eu/nordtal/s2/commands/NordtalUser.java",
            "the default overloads, which are what every other call site is measured against");

    /** {@code .reply(} and everything up to its closing bracket. */
    private static final Pattern CALL = Pattern.compile("\\.reply\\s*\\(");

    @Test
    void everyReplyNamesAToneSoARefusalIsNeverTheColourOfAConfirmation() {
        final List<String> offenders = new ArrayList<>();
        for (final Path source : sources()) {
            final String text = read(source);
            final String name = RepositoryRoot.relative(source);
            if (ALLOWED.containsKey(name)) {
                continue;
            }
            final Matcher matcher = CALL.matcher(text);
            while (matcher.find()) {
                final String call = arguments(text, matcher.end() - 1);
                if (!call.contains("Tone.")) {
                    offenders.add(name + ":"
                            + (text.substring(0, matcher.start())
                                            .chars()
                                            .filter(c -> c == '\n')
                                            .count()
                                    + 1));
                }
            }
        }
        assertEquals(
                List.of(),
                offenders,
                "a reply with no tone is drawn in whatever colour the client was already using -"
                        + " add one of Tone's five, or add the file to ALLOWED with the reason it"
                        + " cannot have one");
    }

    @Test
    void theAllowlistNamesFilesThatExist() {
        for (final String name : ALLOWED.keySet()) {
            assertTrue(
                    Files.isRegularFile(RepositoryRoot.resolve(name)),
                    name + " is allowed to send an untoned reply and does not exist - a stale"
                            + " allowlist entry is a hole nobody can see");
        }
    }

    /** Returns the text between {@code (} at {@code open} and its matching {@code )}. */
    private static String arguments(final String text, final int open) {
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return text.substring(open, i + 1);
                }
            }
        }
        return text.substring(open);
    }

    private static List<Path> sources() {
        final List<Path> found = new ArrayList<>();
        for (final String root : SOURCE_ROOTS) {
            final Path directory = RepositoryRoot.resolve(root);
            if (!Files.isDirectory(directory)) {
                throw new IllegalStateException(root + " is not a directory - the roots have moved");
            }
            try (Stream<Path> tree = Files.walk(directory)) {
                tree.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".java"))
                        .sorted()
                        .forEach(found::add);
            } catch (final IOException e) {
                throw new UncheckedIOException("cannot walk " + root, e);
            }
        }
        return found;
    }

    private static String read(final Path source) {
        try {
            return Files.readString(source, StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot read " + source, e);
        }
    }
}
