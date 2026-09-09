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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A wrapped line in a message bundle keeps the space that separated the two words.
 *
 * <h2>Why this is a test and not a habit</h2>
 * {@code Properties.load} joins a continued line to the next one and <b>strips the next line's
 * leading whitespace</b>, so the indentation that makes a wrapped bundle readable contributes
 * nothing to the value. The separator has to be on the <em>first</em> line, before the backslash.
 * Six values in this repository were written without it on 2026-09-04 and reached a player as
 * {@code neugelesen}, {@code bleibenunverändert}, {@code Theconsole} and {@code sayswhether} - one
 * of them in both languages, all six in text an operator reads after typing a reload command.
 *
 * <p>Nothing else can catch it. The file parses, the key resolves, both languages carry the same
 * keys and the same placeholders, so {@code MessageBundlesTest} is green; the only symptom is two
 * words with no space between them, in a string nobody diffs. The failure mode is invisible in the
 * source too, because the source is where the space <em>looks</em> present - on the following
 * line.</p>
 *
 * <p>The rule is deliberately absolute rather than an allowlist: no value in this repository wants
 * to be split mid-word, and a value that ever did could be written as one long line instead. An
 * absolute rule needs no maintenance and cannot be weakened by accident.</p>
 */
class BundleContinuationTest {

    /**
     * Every module that ships a message bundle - the four Minecraft-facing ones, the bot, and the
     * shared one.
     *
     * <p>{@code commands} was missing here until 2026-09-06, which left the continuation rule
     * unchecked on the one bundle <em>every</em> surface loads - and it carries continued lines.
     */
    private static final List<String> BUNDLE_ROOTS = List.of(
            "smp/src/main/resources/messages",
            "limbo/src/main/resources/messages",
            "hunger-games/src/main/resources/messages",
            "network-control/src/main/resources/messages",
            "discord-bot/src/main/resources/messages",
            "commands/src/main/resources/messages",
            // :paper-common's, new 2026-09-09 with the five system lines. Added here in the same
            // commit that created it, because the last root this list gained - commands' - was
            // added two days after the bundle existed, and for two days the one bundle every
            // surface loads was the one bundle outside these checks.
            "paper-common/src/main/resources/messages");

    @Test
    @DisplayName("a continued line ends with a space, so the two words stay two words")
    void everyContinuationKeepsItsSeparator() {
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
                glued.add(RepositoryRoot.relative(bundle) + ":" + (i + 1)
                        + " renders as \"" + tail(withoutEscape) + head(next) + "\"");
            }
        }
        assertEquals(List.of(), glued,
                "a continued bundle line must end with a space before the backslash - the next"
                        + " line's indentation is stripped, so without it the two words are glued"
                        + " together in what the player reads");
    }

    /**
     * A count in a sentence picks a key; it never picks a parenthesis.
     *
     * <h2>Why an absolute rule</h2>
     * "spin(s)" is not a sentence in English and its German equivalent is worse still: the two this
     * caught on 2026-09-06 read "verdiente Dreh(s)" and "Zugangszeitraum/-raeume von {accounts}
     * Konto/Konten", the second of which is a slash construction a reader has to unpick. Both were
     * player- or admin-facing, and one of them - {@code smp.wheel.none} - was watched being shown to
     * a real client, saying "You have 0 earned spin(s) waiting" underneath a refusal that had
     * already said there were none.
     *
     * <p>The alternative costs one line of Java, and this repository already writes it: the
     * farm-reset warning picks {@code smp.farm.warning.one} at one minute. So the rule is absolute
     * rather than an allowlist - there is no value that genuinely wants a parenthetical plural, and
     * an allowlist is where the next one would go.</p>
     */
    @Test
    @DisplayName("a count picks a key rather than a parenthetical plural")
    void noValueSpellsItsPluralWithAParenthesis() {
        final List<String> parenthesised = new ArrayList<>();
        for (final Path bundle : bundles()) {
            final List<String> lines = read(bundle);
            boolean continued = false;
            for (int i = 0; i < lines.size(); i++) {
                final String line = lines.get(i);
                // The value only: a comment is not read by anybody but us, and this rule is about
                // what a player is shown. A file explaining the rule - "# never write spin(s)" -
                // must not fail the test that enforces it.
                final String value = continued ? line : valueOf(line);
                continued = continues(line);
                if (value == null) {
                    continue;
                }
                for (final String shape : List.of("(s)", "(n)", "(en)", "(e)", "(er)")) {
                    if (value.contains(shape)) {
                        parenthesised.add(RepositoryRoot.relative(bundle) + ":" + (i + 1)
                                + " writes \"" + shape + "\"");
                    }
                }
            }
        }
        assertEquals(List.of(), parenthesised,
                "a bundle value must not spell a plural with a parenthesis - pick a key on the"
                        + " count instead, the way the farm-reset warning does at one minute");
    }

    /**
     * The value a line declares, or {@code null} if it declares none.
     *
     * <p>A properties comment starts with {@code #} or {@code !} after any leading whitespace, and
     * a key ends at the first unescaped {@code =} or {@code :} - or, for a key with no separator at
     * all, at the first unescaped whitespace. A line that is neither a comment nor a declaration is
     * blank.</p>
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
