package eu.nordtal.season.messages;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.common.RepositoryRoot;
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
 * A wrapped line in a message bundle keeps the space between its two words.
 *
 * {@code Properties.load} strips the leading whitespace of a continuation line, so the space
 * belongs on the first line, before the backslash. No value may split a word.
 */
class BundleContinuationTest {

    private static final List<String> BUNDLE_ROOTS = List.of("smp/src/main/resources/messages");

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
     * A count in a sentence selects a {@code .one} key and never a parenthesis such as "spin(s)".
     */
    @Test
    void aCountPicksAKeyRatherThanAParentheticalPlural() {
        final List<String> parenthesised = new ArrayList<>();
        for (final Path bundle : bundles()) {
            final List<String> lines = read(bundle);
            boolean continued = false;
            for (int i = 0; i < lines.size(); i++) {
                final String line = lines.get(i);
                // Only the value is checked, so a comment that explains the rule does not fail.
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

    /** An odd number of trailing backslashes continues the line. */
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
