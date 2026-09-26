package eu.nordtal.s2.common.message;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.s2.common.RepositoryRoot;
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
 * Checks that a wrapped line in a message bundle keeps the space that separated the two words.
 *
 * {@code Properties.load} strips the continuation line's leading whitespace, so the separator has to
 * be on the first line, before the backslash. The rule is absolute: no value wants a mid-word split.
 */
class BundleContinuationTest {

    /** Every module that ships a message bundle. */
    private static final List<String> BUNDLE_ROOTS = List.of(
            "smp/src/main/resources/messages",
            "limbo/src/main/resources/messages",
            "hunger-games/src/main/resources/messages",
            "proxy/src/main/resources/messages",
            "discord-bot/src/main/resources/messages",
            "commands/src/main/resources/messages",
            "paper-common/src/main/resources/messages");

    @Test
    void aContinuedLineEndsWithASpaceSoTheTwoWordsStayTwoWords() {
        final List<String> glued = new ArrayList<>();
        for (final Path bundle : bundles()) {
            final List<String> lines = read(bundle);
            for (int i = 0; i < lines.size(); i++) {
                final String line = lines.get(i);
                if (!continues(line)) {
                    continue;
                }
                final String withoutEscape = line.substring(0, line.length() - 1);
                if (withoutEscape.endsWith(" ")) {
                    continue;
                }
                final String next = i + 1 < lines.size() ? lines.get(i + 1).strip() : "";
                glued.add(RepositoryRoot.relative(bundle) + ":" + (i + 1) + " renders as \"" + tail(withoutEscape)
                        + head(next) + "\"");
            }
        }
        assertEquals(
                List.of(),
                glued,
                "a continued bundle line must end with a space before the backslash - the next"
                        + " line's indentation is stripped, so without it the two words are glued"
                        + " together in what the player reads");
    }

    /**
     * Checks that a count in a sentence picks a key, never a parenthesis such as "spin(s)".
     *
     * The rule is absolute; a count picks a {@code .one} key instead.
     */
    @Test
    void aCountPicksAKeyRatherThanAParentheticalPlural() {
        final List<String> parenthesised = new ArrayList<>();
        for (final Path bundle : bundles()) {
            final List<String> lines = read(bundle);
            boolean continued = false;
            for (int i = 0; i < lines.size(); i++) {
                final String line = lines.get(i);
                // The value only, so a comment explaining the rule does not fail it.
                final String value = continued ? line : valueOf(line);
                continued = continues(line);
                if (value == null) {
                    continue;
                }
                for (final String shape : List.of("(s)", "(n)", "(en)", "(e)", "(er)")) {
                    if (value.contains(shape)) {
                        parenthesised.add(
                                RepositoryRoot.relative(bundle) + ":" + (i + 1) + " writes \"" + shape + "\"");
                    }
                }
            }
        }
        assertEquals(
                List.of(),
                parenthesised,
                "a bundle value must not spell a plural with a parenthesis - pick a key on the"
                        + " count instead, the way the wheel does at one spin");
    }

    /**
     * The value a line declares, or {@code null} if it declares none.
     *
     * A properties comment starts with {@code #} or {@code !} after any leading whitespace, and
     * a key ends at the first unescaped {@code =} or {@code :} - or, for a key with no separator at
     * all, at the first unescaped whitespace. A line that is neither a comment nor a declaration is
     * blank.
     */
    private static String valueOf(final String line) {
        final String trimmed = line.stripLeading();
        if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
            return null;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            final char character = trimmed.charAt(i);
            if (character == '\\') {
                i++;
                continue;
            }
            if (character == '=' || character == ':' || Character.isWhitespace(character)) {
                return trimmed.substring(i + 1);
            }
        }
        return "";
    }

    /**
     * @return whether {@code line} is continued on the next one - an <em>odd</em> number of trailing
     *         backslashes, because {@code \\} at the end of a value is an escaped backslash and ends
     *         the value
     */
    private static boolean continues(final String line) {
        int backslashes = 0;
        for (int i = line.length() - 1; i >= 0 && line.charAt(i) == '\\'; i--) {
            backslashes++;
        }
        return backslashes % 2 == 1;
    }

    private static String tail(final String line) {
        return line.length() <= 16 ? line : "…" + line.substring(line.length() - 16);
    }

    private static String head(final String line) {
        return line.length() <= 16 ? line : line.substring(0, 16) + "…";
    }

    private static List<Path> bundles() {
        final List<Path> found = new ArrayList<>();
        for (final String root : BUNDLE_ROOTS) {
            final Path directory = RepositoryRoot.resolve(root);
            try (Stream<Path> tree = Files.walk(directory)) {
                tree.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".properties"))
                        .sorted()
                        .forEach(found::add);
            } catch (final IOException e) {
                throw new UncheckedIOException("cannot walk " + root, e);
            }
        }
        if (found.isEmpty()) {
            throw new IllegalStateException("no message bundles found - the roots have moved");
        }
        return found;
    }

    private static List<String> read(final Path bundle) {
        try {
            return Files.readAllLines(bundle, StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot read " + bundle, e);
        }
    }
}
