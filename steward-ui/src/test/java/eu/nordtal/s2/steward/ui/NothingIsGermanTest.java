package eu.nordtal.s2.steward.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
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
 * Nothing in Steward's three services, their deploy script or the compose file is German.
 *
 * The word list is the German bundle minus the English one, plus {@code language-rules.json}, matched by stem.
 */
class NothingIsGermanTest {

    /** What Steward is: three services, the script that puts them on a host, the compose file. */
    private static final List<String> TREES = List.of("steward-ui/src", "steward-worker/src", "steward-deployer/src");

    private static final List<String> FILES = List.of(
            "deploy/nordtal.sh",
            "steward-worker/README.md",
            "steward-deployer/README.md",
            "deploy/README.md",
            "compose.yml");

    private static final String BUNDLE = "commands/src/main/resources/messages/commands/";
    private static final String RULES = "steward-ui/language-rules.json";

    /** The files whose subject is German, exempted by file name and no wider, since Steward edits the bot's bundles. */
    private static final Set<String> ABOUT_GERMAN =
            Set.of("NothingIsGermanTest.java", "MessageBundlesTest.java", "MessagesApiIntegrationTest.java");

    /** Three or more letters, German ones included; two-letter words are noise in both languages. */
    private static final Pattern WORD = Pattern.compile("[A-Za-zÄÖÜäöüß]{3,}");

    /**
     * The same word, but only where a reader would see one, so {@code _} and a digit are part of it as in {@code \b}.
     */
    private static final Pattern SOURCE_WORD = Pattern.compile("(?<![\\wÄÖÜäöüß])[A-Za-zÄÖÜäöüß]{3,}(?![\\wÄÖÜäöüß])");

    /** The hand-kept half of the rule; the rest of the list is computed. */
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
        // A guard that silently stops guarding is worse than none: this fires if the bundle moves or the parse breaks.
        assertTrue(
                forbidden.size() > 200,
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
        assertTrue(isGerman("Befehle", german), "inflection: the bundle only says Befehl");
        assertTrue(isGerman("Konfiguration", german), "compounding: the bundle only says Konfigurationsdateien");
        assertTrue(isGerman("Sperre", german), "Sperre is in language-rules.json by hand");

        // `started` and `stopped` extend the German `starte` and `stoppe` by an English `d`, not a listed tail.
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

    /** German-only bundle words, minus what is also English, plus what leaked and is in no bundle. */
    private static Set<String> forbidden() {
        final Rules rules = rules();
        final Set<String> allowed = lowercased(rules.alsoEnglish());
        final Set<String> english = bundleWords("en.properties");

        final Set<String> forbidden = new TreeSet<>();
        for (final String word : bundleWords("de.properties")) {
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

    /** Every word in the values of a message bundle, lowercased. Never in its keys. */
    private static Set<String> bundleWords(final String name) {
        final Path file = repository().resolve(BUNDLE + name);
        assertTrue(
                Files.isRegularFile(file),
                file + " is not there, so this test derived its word"
                        + " list from nothing. Fix the path rather than the assertion.");
        final Set<String> words = new HashSet<>();
        for (final String line : lines(file)) {
            final int separator = line.indexOf('=');
            if (separator == -1 || line.stripLeading().startsWith("#")) {
                continue;
            }
            final Matcher matcher = WORD.matcher(line.substring(separator + 1));
            while (matcher.find()) {
                words.add(matcher.group().toLowerCase(Locale.ROOT));
            }
        }
        return words;
    }

    /** Parsed once, since every word of every scanned line asks. */
    private static final Rules RULES_FILE = rules();

    private static final Set<String> ALSO_ENGLISH = lowercased(RULES_FILE.alsoEnglish());

    private static Rules rules() {
        final Path file = repository().resolve(RULES);
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
     * Whether one word out of a source file is German, by stem as well as equality.
     *
     * A derived word plus a German ending is inflection; a derived word continued is compounding.
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

    /** Every line holding a word this rule calls German. */
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
        final Path root = repository();
        final List<Path> files = new ArrayList<>();
        for (final String tree : TREES) {
            final Path directory = root.resolve(tree);
            assertTrue(
                    Files.isDirectory(directory),
                    directory + " is not there any more, so this"
                            + " test was scanning nothing. Fix the path rather than the assertion.");
            try (Stream<Path> walk = Files.walk(directory)) {
                walk.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".java"))
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

    /** The repository root, found by walking up to the build definition, so a wrong guess cannot scan nothing. */
    private static Path repository() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null && !Files.isRegularFile(directory.resolve("settings.gradle.kts"))) {
            directory = directory.getParent();
        }
        assertTrue(
                directory != null, "no settings.gradle.kts above " + Path.of("").toAbsolutePath());
        return directory;
    }
}
