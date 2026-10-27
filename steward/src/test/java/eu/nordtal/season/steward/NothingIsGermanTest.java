package eu.nordtal.season.steward;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import eu.nordtal.season.common.RepositoryRoot;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Checks that no German is in the steward modules.
 * They are steward, steward-agent, steward-bunq, the admin texts, their deploy script and the compose file.
 *
 * The word list is the German texts of the bot's documents minus the English ones, plus
 * {@code language-rules.json}, matched by stem.
 */
class NothingIsGermanTest {

    private static final List<String> TREES =
            List.of("steward/src", "steward-agent/src", "steward-bunq/src", "internal-api/src");

    private static final List<String> FILES = List.of(
            "deploy/nordtal.sh",
            "steward/README.md",
            "steward-agent/README.md",
            "steward-bunq/README.md",
            "internal-api/README.md",
            "database/src/main/resources/messages/admin/en.properties",
            "deploy/README.md",
            "compose.yml");

    private static final String BUNDLE = "discord-bot/src/main/resources/documents/discord";
    private static final String RULES = "steward/language-rules.json";

    /** The files whose subject is German, exempted by exact file name: Steward edits and searches the bot's bundles. */
    private static final Set<String> ABOUT_GERMAN = Set.of(
            "NothingIsGermanTest.java",
            "MessageBundlesTest.java",
            "MessagesApiIntegrationTest.java",
            "TranslationSearchTest.java");

    private static final Pattern WORD = Pattern.compile("[A-Za-zÄÖÜäöüß]{3,}");

    /** The same word, but only where a reader sees one, so {@code _} and a digit belong to it as in {@code \b}. */
    private static final Pattern SOURCE_WORD = Pattern.compile("(?<![\\wÄÖÜäöüß])[A-Za-zÄÖÜäöüß]{3,}(?![\\wÄÖÜäöüß])");

    private record Rules(
            List<String> alsoEnglish,
            List<String> extra,
            List<String> abbreviations,
            int minStem,
            int minPrefix,
            int minRest,
            List<String> tails,
            List<String> shapes) {}

    @Test
    void theListHasASource() {
        final Set<String> forbidden = forbidden();
        // Fails if the bundle moves or the parse breaks, so the guard cannot go empty unseen.
        assertTrue(
                forbidden.size() > 150,
                "only " + forbidden.size() + " German words were derived from " + BUNDLE
                        + " - the bundle moved, or the values stopped being parsed.");
        assertTrue(forbidden.contains("vergleich"), "a word that actually leaked is not in the list");
        assertFalse(
                forbidden.contains("stand"), "`stand` is an English word and is in language-rules.json's alsoEnglish");
    }

    @Test
    void noGermanWord() {
        assertEquals(
                List.of(),
                offences(forbidden()),
                "Steward is English - the interface, the logs, the comments and the files it"
                        + " writes. The bot is the bilingual half and has its own bundles.");
    }

    @Test
    void stemsAndNotOnlyWholeWords() {
        final Set<String> german = forbidden();
        assertTrue(isGerman("Befehle", german), "inflection: the list only says Befehl");
        assertTrue(isGerman("Konfiguration", german), "compounding: the list only says Konfigurationsdateien");
        assertTrue(isGerman("Sperre", german), "Sperre is in language-rules.json by hand");

        // `started` and `stopped` extend the German `starte` and `stoppe` by an English `d`, not by a listed tail.
        for (final String word : List.of(
                "Configuration",
                "Commands",
                "Status",
                "Backup",
                "Service",
                "Restore",
                "started",
                "stopped",
                "argument",
                "Operations")) {
            assertFalse(isGerman(word, german), word + " is English and was called German");
        }
    }

    @Test
    void noGermanShape() {
        final List<String> found = new ArrayList<>();
        for (final String shape : rules().shapes()) {
            found.addAll(offences(Pattern.compile(shape, Pattern.CASE_INSENSITIVE)));
        }
        assertEquals(
                List.of(),
                found,
                "these find German by its shape rather than by a list, which is the only thing"
                        + " that reaches a word the bot has never said.");
    }

    @Test
    void noGermanAbbreviation() {
        final Rules rules = rules();
        assertEquals(
                List.of(),
                offences(Pattern.compile(String.join("|", rules.abbreviations()))),
                "`z. B.` is not `e.g.` - and it has no word boundary, which is why it needs its own"
                        + " pattern rather than a place in the list.");
    }

    private static Set<String> forbidden() {
        final Rules rules = rules();
        final Set<String> allowed = lowercased(rules.alsoEnglish());
        final Set<String> english = bundleWords("en");

        final Set<String> forbidden = new TreeSet<>();
        for (final String word : bundleWords("de")) {
            if (!english.contains(word) && !allowed.contains(word)) {
                forbidden.add(word);
            }
        }
        for (final String word : lowercased(rules.extra())) {
            if (!allowed.contains(word)) {
                forbidden.add(word);
            }
        }
        return forbidden;
    }

    private static Set<String> bundleWords(final String language) {
        final Path directory = RepositoryRoot.path().resolve(BUNDLE);
        assertTrue(
                Files.isDirectory(directory),
                directory + " is not there, so this test derived its word"
                        + " list from nothing. Fix the path rather than the assertion.");
        final Pattern text = Pattern.compile("\"" + language + "\":\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
        final Set<String> words = new HashSet<>();
        try (Stream<Path> files = Files.list(directory)) {
            for (final Path file :
                    files.filter(path -> path.toString().endsWith(".json")).toList()) {
                final Matcher texts = text.matcher(String.join("\n", lines(file)));
                while (texts.find()) {
                    final Matcher matcher = WORD.matcher(texts.group(1));
                    while (matcher.find()) {
                        words.add(matcher.group().toLowerCase(Locale.ROOT));
                    }
                }
            }
        } catch (final IOException failure) {
            throw new UncheckedIOException(failure);
        }
        return words;
    }

    private static final Rules RULES_FILE = rules();

    private static final Set<String> ALSO_ENGLISH = lowercased(RULES_FILE.alsoEnglish());

    private static Rules rules() {
        final Path file = RepositoryRoot.path().resolve(RULES);
        assertTrue(Files.isRegularFile(file), file + " is not there, and it is half of this rule.");
        try {
            return new Gson().fromJson(Files.readString(file, StandardCharsets.UTF_8), Rules.class);
        } catch (final IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    private static Set<String> lowercased(final Collection<String> words) {
        return words.stream().map(word -> word.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
    }

    /**
     * Returns whether a word of a source file is German, by stem as well as equality.
     *
     * A derived word plus a German ending is inflection. A derived word continued is compounding.
     */
    private static boolean isGerman(final String word, final Set<String> german) {
        final Rules rules = RULES_FILE;
        final String lower = word.toLowerCase(Locale.ROOT);
        if (ALSO_ENGLISH.contains(lower)) {
            return false;
        }
        if (german.contains(lower)) {
            return true;
        }
        for (final String known : german) {
            if (known.length() >= rules.minStem()
                    && lower.startsWith(known)
                    && rules.tails().contains(lower.substring(known.length()))) {
                return true;
            }
            if (lower.length() >= rules.minPrefix()
                    && known.startsWith(lower)
                    && known.length() - lower.length() >= rules.minRest()) {
                return true;
            }
        }
        return false;
    }

    private static List<String> offences(final Set<String> german) {
        return scan(line -> {
            final Matcher matcher = SOURCE_WORD.matcher(line);
            while (matcher.find()) {
                if (isGerman(matcher.group(), german)) {
                    return true;
                }
            }
            return false;
        });
    }

    private static List<String> offences(final Pattern pattern) {
        return scan(line -> pattern.matcher(line).find());
    }

    private static List<String> scan(final java.util.function.Predicate<String> guilty) {
        final Path root = RepositoryRoot.path();
        final List<Path> files = new ArrayList<>();
        for (final String tree : TREES) {
            final Path directory = root.resolve(tree);
            assertTrue(
                    Files.isDirectory(directory),
                    directory + " is not there any more, so this"
                            + " test was scanning nothing. Fix the path rather than the assertion.");
            try (Stream<Path> walk = Files.walk(directory)) {
                walk.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".java")
                                || path.getFileName().toString().endsWith(".properties"))
                        .filter(path ->
                                !ABOUT_GERMAN.contains(path.getFileName().toString()))
                        .forEach(files::add);
            } catch (final IOException unreadable) {
                throw new UncheckedIOException(unreadable);
            }
        }
        for (final String name : FILES) {
            final Path file = root.resolve(name);
            assertTrue(
                    Files.isRegularFile(file),
                    file + " is not there any more, so this test was"
                            + " scanning less than it says. Fix the path rather than the assertion.");
            files.add(file);
        }

        final List<String> found = new ArrayList<>();
        for (final Path file : files) {
            final List<String> lines = lines(file);
            for (int number = 0; number < lines.size(); number++) {
                if (guilty.test(lines.get(number))) {
                    found.add(root.relativize(file) + ":" + (number + 1) + ": "
                            + lines.get(number).strip());
                }
            }
        }
        return found;
    }

    private static List<String> lines(final Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (final IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }
}
