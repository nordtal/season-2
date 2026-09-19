package eu.nordtal.s2.common.message;

import eu.nordtal.s2.common.RepositoryRoot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every message bundle in the repository, in both languages, complete.
 *
 * <p><b>Why here and not once per module.</b> Four of the seven modules that ship a bundle had a
 * parity guard of their own and three did not - {@code discord-bot}, {@code limbo} and
 * {@code paper-common}. The gap is invisible by construction: English is {@code Messages}'
 * fallback for everything, so a key with no German is answered <i>in English</i>. Nothing throws,
 * nothing is logged, no test fails - a German player simply gets one English line in the middle of
 * German text, which is the least likely kind of defect to be reported by anyone. Copying the same
 * ninety lines into three more modules would have closed today's gap and left the next module to
 * discover the rule by not having it. This walks the tree instead, so a module that gains a bundle
 * is covered the day it gains one, without anybody remembering anything.</p>
 *
 * <p>It does not replace the per-module tests. Those also check that every key <i>named in code</i>
 * exists, which needs the module's own sources and is a different question from parity.</p>
 *
 * <p>The files are declared in {@code common/build.gradle.kts} through
 * {@code repositoryRootTestInputs}. Without that Gradle cannot see them and an edit to a bundle
 * leaves {@code :common:test} UP-TO-DATE - the failure mode this whole mechanism exists for.</p>
 */
class EveryBundleIsCompleteTest {

    /**
     * The bundles that existed when this test was written.
     *
     * <p>It is a floor, never a ceiling: the walk below finds bundles on its own, so a new module
     * needs no line here. What the list catches is the opposite and nastier case - a walk that
     * silently finds nothing because somebody moved {@code messages/} or renamed
     * {@code resources}. A green test over zero bundles looks exactly like a green test over
     * seven.</p>
     */
    private static final Set<String> KNOWN = Set.of(
            "commands/src/main/resources/messages/commands",
            "discord-bot/src/main/resources/messages/access",
            "hunger-games/src/main/resources/messages/hunger-games",
            "limbo/src/main/resources/messages/limbo",
            "proxy/src/main/resources/messages/proxy",
            "paper-common/src/main/resources/messages/paper-common",
            "smp/src/main/resources/messages/smp");

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z0-9_-]+)}");

    @Test
    @DisplayName("the walk finds every bundle this repository had when the test was written")
    void theWalkStillFindsTheBundlesItWasWrittenFor() {
        assertTrue(bundles().keySet().containsAll(KNOWN),
                "the walk lost sight of a bundle it used to find. Missing: "
                        + missingFrom(bundles().keySet()));
    }

    @Test
    @DisplayName("a bundle ships both languages, because German is not a fallback")
    void everyBundleShipsBothLanguages() {
        final Map<String, Set<String>> incomplete = new TreeMap<>();
        bundles().forEach((name, languages) -> {
            if (!languages.equals(Set.of("en", "de"))) {
                incomplete.put(name, languages);
            }
        });
        assertEquals(Map.of(), incomplete,
                "the season ships two languages, and a bundle with one of them is half a bundle");
    }

    @Test
    @DisplayName("every key exists in both languages")
    void everyKeyExistsInBothLanguages() {
        final Map<String, Set<String>> untranslated = new TreeMap<>();
        for (final String name : bundles().keySet()) {
            final Set<String> english = keysOf(name, "en");
            final Set<String> german = keysOf(name, "de");

            final Set<String> onlyEnglish = new TreeSet<>(english);
            onlyEnglish.removeAll(german);
            final Set<String> onlyGerman = new TreeSet<>(german);
            onlyGerman.removeAll(english);

            if (!onlyEnglish.isEmpty()) {
                untranslated.put(name + " has no German for", onlyEnglish);
            }
            if (!onlyGerman.isEmpty()) {
                untranslated.put(name + " has German with no English original for", onlyGerman);
            }
        }
        assertEquals(Map.of(), untranslated,
                "a key with no translation is not an error anybody sees - English is the fallback,"
                        + " so it is answered in English and nothing anywhere says so");
    }

    @Test
    @DisplayName("a translation uses the same placeholders as its original")
    void thePlaceholdersOfATranslationMatchItsOriginal() {
        final Map<String, String> wrong = new TreeMap<>();
        for (final String name : bundles().keySet()) {
            final Properties english = load(name, "en");
            final Properties german = load(name, "de");

            for (final String key : english.stringPropertyNames()) {
                final String translation = german.getProperty(key);
                if (translation == null) {
                    continue; // everyKeyExistsInBothLanguages says this, and says it better
                }
                final Set<String> original = placeholders(english.getProperty(key));
                if (!original.equals(placeholders(translation))) {
                    wrong.put(name + "/" + key,
                            "en " + original + " vs de " + placeholders(translation));
                }
            }
        }
        assertEquals(Map.of(), wrong,
                "one of these prints a literal {name} to somebody, and the other does not");
    }

    /**
     * Every {@code <module>/src/main/resources/messages/<bundle>} in the repository, against the
     * language codes lying in it.
     */
    private static Map<String, Set<String>> bundles() {
        final Map<String, Set<String>> found = new TreeMap<>();
        for (final Path module : childDirectories(RepositoryRoot.path())) {
            final Path messages = module.resolve("src/main/resources/messages");
            if (!Files.isDirectory(messages)) {
                continue;
            }
            for (final Path bundle : childDirectories(messages)) {
                found.put(RepositoryRoot.relative(bundle), languagesIn(bundle));
            }
        }
        return found;
    }

    private static Set<String> languagesIn(final Path bundle) {
        try (Stream<Path> files = Files.list(bundle)) {
            final Set<String> languages = new TreeSet<>();
            files.map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".properties"))
                    .forEach(name -> languages.add(name.substring(0, name.length() - ".properties".length())));
            return languages;
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot list " + bundle, e);
        }
    }

    private static List<Path> childDirectories(final Path directory) {
        try (Stream<Path> children = Files.list(directory)) {
            return children.filter(Files::isDirectory).sorted().toList();
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot list " + directory, e);
        }
    }

    private static Set<String> missingFrom(final Set<String> found) {
        final Set<String> missing = new TreeSet<>(KNOWN);
        missing.removeAll(found);
        return missing;
    }

    private static Set<String> keysOf(final String bundle, final String language) {
        return new TreeSet<>(load(bundle, language).stringPropertyNames());
    }

    /**
     * Read as UTF-8 through a {@link Reader}, never through the {@code InputStream} overload.
     * {@code .properties} is historically Latin-1 and {@link Properties#load(java.io.InputStream)}
     * still reads it that way, which turns every umlaut in the German half into two characters -
     * and then reports the key as present, so the parity check above would stay green while the
     * text was already wrong.
     */
    private static Properties load(final String bundle, final String language) {
        final Properties properties = new Properties();
        final Path file = RepositoryRoot.resolve(bundle + "/" + language + ".properties");
        try (Reader reader = new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
        return properties;
    }

    private static Set<String> placeholders(final String text) {
        final Set<String> names = new TreeSet<>();
        final Matcher matcher = PLACEHOLDER.matcher(text);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }
}
