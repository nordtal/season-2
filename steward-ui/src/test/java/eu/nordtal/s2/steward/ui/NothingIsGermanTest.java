package eu.nordtal.s2.steward.ui;

import com.google.gson.Gson;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Nothing in Steward is German - the three services, the script that deploys them and the compose
 * file they are described in.
 *
 * <h2>Why a test and not a rule</h2>
 * It was a rule, it was carried out by hand, and it drifted. {@code Ampel} survived in
 * {@link eu.nordtal.s2.steward.ui.config.UiSpec}'s {@code @Comment} text, which is not an internal
 * note at all: jcore writes those comments into {@code steward-ui.yml}, so the one German word left
 * in this module was in the file an operator opens.
 *
 * <h2>The word list is derived, and that is what makes it a guard</h2>
 * The first version of this test, and of the frontend's, carried a hand-written list of about sixty
 * words. It was written on 2026-09-14 and by the next morning it had missed nine German strings a
 * person could read on screen - a button that said {@code Abschicken}, a column head that said
 * {@code Vergleich}, and the start page's own all-clear sentence, which was
 * {@code "Alles in Ordnung."}. Sixty words is not a rule. It is a memory of the sixty words
 * somebody had already noticed.
 *
 * <p>So the list has a source. {@code commands/.../de.properties} is a corpus of real German this
 * project maintains anyway, and every word in it that is <b>not</b> also in {@code en.properties}
 * is forbidden here. That subtraction is what keeps {@code Server}, {@code Status} and
 * {@code Discord} out of the way, and it means the guard grows whenever the bot's German does.</p>
 *
 * <p>What a derivation cannot know lives in {@code steward-ui/language-rules.json}: the words that
 * are German in the bundle and English here ({@code die}, {@code stand}, {@code spawn}), the ones
 * that leaked and are in no bundle ({@code Ampel}, {@code Strg}), and the abbreviations, which are
 * not words and have no boundary to match on. The frontend's guard reads that same file - one rule,
 * one place, two runners, because each sees files the other cannot.</p>
 *
 * <h2>Two more things, because on 2026-09-14 the derivation alone walked past four words</h2>
 * {@code Befehle}, {@code Konfiguration}, {@code Gelaufen} and {@code Sperre} were all on the
 * screen with every test green. Three are answered without one word being added by hand:
 *
 * <ul>
 *   <li><b>Stems, not whole words.</b> The bundle says {@code Befehl} and Steward said
 *       {@code Befehle}; the bundle says {@code Konfigurationsdateien} and Steward said
 *       {@code Konfiguration}. A derived list knows only the forms the bot happens to use, and
 *       German inflects and compounds.</li>
 *   <li><b>Shape, not vocabulary.</b> {@code Gelaufen} is in no bundle and no stem of it is
 *       either. The {@code shapes} patterns find German by its endings.</li>
 * </ul>
 *
 * <p>{@code Sperre} is the honest fourth and is in {@code extra} by hand: a German word with no
 * German ending that the bot never says cannot be derived or recognised, and a guard that hides
 * its blind spot is worse than one that has none.</p>
 *
 * <h2>There is no umlaut rule here, unlike on the frontend</h2>
 * {@code LogFrames} explains chunked UTF-8 with the sentence "an ä in a death message", and
 * that character is the subject of the comment rather than a word of German: a rule that failed on
 * it would be teaching the code to describe bytes worse.
 *
 * <p>The bot is the bilingual half of this project and is deliberately not scanned - that is what
 * its language configuration is for.</p>
 */
class NothingIsGermanTest {

    /** What Steward is: three services, the script that puts them on a host, the compose file. */
    private static final List<String> TREES = List.of(
            "steward-ui/src", "steward-worker/src", "steward-deployer/src");
    private static final List<String> FILES = List.of(
            "deploy/setup.sh", "steward-worker/README.md", "steward-deployer/README.md",
            "deploy/README.md", "compose.yml");

    private static final String BUNDLE = "commands/src/main/resources/messages/commands/";
    private static final String RULES = "steward-ui/language-rules.json";

    /** Three or more letters, German ones included. Two-letter words are noise in both languages. */
    private static final Pattern WORD = Pattern.compile("[A-Za-zÄÖÜäöüß]{3,}");

    /**
     * The same word, but only where a reader would see one - and an identifier is not one.
     *
     * {@code _} and a digit count as part of the word, exactly as {@code \b} treats them, because
     * that is what kept {@code NORDTAL_STEWARD_UI_CONFIG_DIR} quiet while {@code dir} sat in the
     * German bundle. Splitting on letters alone finds {@code DIR} in there and is wrong: nobody
     * reads an environment variable as a sentence.
     */
    private static final Pattern SOURCE_WORD =
            Pattern.compile("(?<![\\wÄÖÜäöüß])[A-Za-zÄÖÜäöüß]{3,}(?![\\wÄÖÜäöüß])");

    /** The hand-kept half of the rule. Everything else about the list is computed. */
    private record Rules(List<String> alsoEnglish, List<String> extra, List<String> abbreviations,
                         int minStem, int minPrefix, int minRest, List<String> tails,
                         List<String> shapes) {
    }

    @Test
    @DisplayName("the word list is derived from the bot's bundle and is not a short one")
    void theListHasASource() {
        final Set<String> forbidden = forbidden();
        // A guard that silently stops guarding is worse than none, because the build stays green.
        // If the bundle moves or the parse breaks, this is the assertion that says so.
        assertTrue(forbidden.size() > 300,
                "only " + forbidden.size() + " German words were derived from " + BUNDLE
                        + " - the bundle moved, or the values stopped being parsed.");
        assertTrue(forbidden.contains("vergleich"), "a word that actually leaked is not in the list");
        assertFalse(forbidden.contains("stand"),
                "`stand` is an English word and is in language-rules.json's alsoEnglish");
    }

    @Test
    @DisplayName("no German word is in any of Steward's own files")
    void noGermanWord() {
        assertEquals(List.of(), offences(forbidden()),
                "Steward is English - the interface, the logs, the comments and the files it"
                        + " writes. The bot is the bilingual half and has its own bundles.");
    }

    @Test
    @DisplayName("a German word is known by its stem, not only by the form the bot happens to use")
    void stemsAndNotOnlyWholeWords() {
        final Set<String> german = forbidden();
        assertTrue(isGerman("Befehle", german), "inflection: the bundle only says Befehl");
        assertTrue(isGerman("Konfiguration", german),
                "compounding: the bundle only says Konfigurationsdateien");
        assertTrue(isGerman("Sperre", german), "Sperre is in language-rules.json by hand");

        // `started` and `stopped` are the two a looser rule flagged: they extend the German
        // `starte` and `stoppe` by an English `d`, which is not one of the tails.
        for (final String word : List.of("Configuration", "Commands", "Status", "Backup",
                "Service", "Restore", "started", "stopped", "argument", "Operations")) {
            assertFalse(isGerman(word, german), word + " is English and was called German");
        }
    }

    @Test
    @DisplayName("nothing has a German ending either")
    void noGermanShape() {
        final List<String> found = new ArrayList<>();
        for (final String shape : rules().shapes()) {
            found.addAll(offences(Pattern.compile(shape, Pattern.CASE_INSENSITIVE)));
        }
        assertEquals(List.of(), found,
                "these find German by its shape rather than by a list, which is the only thing"
                        + " that reaches a word the bot has never said.");
    }

    @Test
    @DisplayName("no German abbreviation either")
    void noGermanAbbreviation() {
        final Rules rules = rules();
        assertEquals(List.of(), offences(Pattern.compile(String.join("|", rules.abbreviations()))),
                "`z. B.` is not `e.g.` - and it has no word boundary, which is why it needs its own"
                        + " pattern rather than a place in the list.");
    }

    // --- the list ------------------------------------------------------------------------------

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

    /** Every word in the <em>values</em> of a message bundle, lowercased. Never in its keys. */
    private static Set<String> bundleWords(final String name) {
        final Path file = repository().resolve(BUNDLE + name);
        assertTrue(Files.isRegularFile(file), file + " is not there, so this test derived its word"
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

    /**
     * Parsed once. It is asked of every word of every line of every scanned file, and re-reading
     * and re-parsing a JSON file that often is what made the frontend's half take 27 seconds.
     */
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
     * Whether one word out of a source file is German - by stem, not only by equality.
     *
     * <p>Two cases, and they are different mistakes. <b>Inflection</b>: the word is a derived one
     * plus a German ending, which is how {@code Befehle} got past a list holding {@code Befehl}.
     * <b>Compounding</b>: a derived word continues it, which is how {@code Konfiguration} got past
     * a list holding {@code Konfigurationsdateien}. The ending list is German-only on purpose -
     * {@code stopped} is {@code stoppe} plus a {@code d}, and {@code d} is not one of them.</p>
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
            if (known.length() >= rules.minStem() && lower.startsWith(known)
                    && rules.tails().contains(lower.substring(known.length()))) {
                return true;
            }
            if (lower.length() >= rules.minPrefix() && known.startsWith(lower)
                    && known.length() - lower.length() >= rules.minRest()) {
                return true;
            }
        }
        return false;
    }

    // --- the scan ------------------------------------------------------------------------------

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
            assertTrue(Files.isDirectory(directory), directory + " is not there any more, so this"
                    + " test was scanning nothing. Fix the path rather than the assertion.");
            try (Stream<Path> walk = Files.walk(directory)) {
                walk.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".java"))
                        // This file is about German words, which is the whole of the exception.
                        .filter(path -> !path.getFileName().toString()
                                .equals("NothingIsGermanTest.java"))
                        .forEach(files::add);
            } catch (final IOException unreadable) {
                throw new UncheckedIOException(unreadable);
            }
        }
        for (final String name : FILES) {
            final Path file = root.resolve(name);
            assertTrue(Files.isRegularFile(file), file + " is not there any more, so this test was"
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

    /**
     * The repository root, found rather than assumed.
     *
     * <p>A test's working directory is its module, and every path here is relative to the root, so
     * getting this wrong would not fail - it would quietly scan nothing. Walking up to the file
     * that defines the build is the one landmark that cannot move.</p>
     */
    private static Path repository() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null && !Files.isRegularFile(directory.resolve("settings.gradle.kts"))) {
            directory = directory.getParent();
        }
        assertTrue(directory != null, "no settings.gradle.kts above " + Path.of("").toAbsolutePath());
        return directory;
    }
}
