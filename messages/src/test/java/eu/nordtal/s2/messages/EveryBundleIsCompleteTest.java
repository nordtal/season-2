package eu.nordtal.s2.messages;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.common.RepositoryRoot;
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
import org.junit.jupiter.api.Test;

/**
 * Checks that every message bundle in the repository has every key in both languages.
 *
 * A missing German key falls back to English silently, so the tree is walked to cover new modules without a line here.
 */
class EveryBundleIsCompleteTest {

    /** The bundles known to exist, a floor that fails a walk which silently finds nothing. */
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
    void theWalkFindsEveryKnownBundle() {
        assertTrue(
                bundles().keySet().containsAll(KNOWN),
                "the walk does not find every known bundle. Missing: "
                        + missingFrom(bundles().keySet()));
    }

    @Test
    void aBundleShipsBothLanguagesBecauseGermanIsNotAFallback() {
        final Map<String, Set<String>> incomplete = new TreeMap<>();
        bundles().forEach((name, languages) -> {
            if (!languages.equals(Set.of("en", "de"))) {
                incomplete.put(name, languages);
            }
        });
        assertEquals(
                Map.of(), incomplete, "the season ships two languages, and a bundle with one of them is half a bundle");
    }

    @Test
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
        assertEquals(
                Map.of(),
                untranslated,
                "a key with no translation is not an error anybody sees - English is the fallback,"
                        + " so it is answered in English and nothing anywhere says so");
    }

    @Test
    void aTranslationUsesTheSamePlaceholdersAsItsOriginal() {
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
                    wrong.put(name + "/" + key, "en " + original + " vs de " + placeholders(translation));
                }
            }
        }
        assertEquals(Map.of(), wrong, "one of these prints a literal {name} to somebody, and the other does not");
    }

    /** Returns every message bundle directory in the repository, with the language codes in it. */
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

    /** Reads a bundle as UTF-8 through a {@link Reader}, since the {@code InputStream} overload reads Latin-1. */
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
