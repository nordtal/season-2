package eu.nordtal.s2.hungergames;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.common.message.Messages;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/** Checks that the two language files hold the same keys, since {@code Messages} shows a missing key verbatim. */
class MessageBundlesTest {

    private static final String ROOT = "messages/hunger-games";

    private final Messages messages =
            Messages.load(MessageBundlesTest.class.getClassLoader(), ROOT, Locale.ENGLISH, Locale.GERMAN);

    @Test
    void bothBundlesAreLoaded() {
        assertTrue(messages.languages().contains("en"));
        assertTrue(
                messages.languages().contains("de"),
                "German is not a fallback language, it is one of the two the season ships");
    }

    @Test
    void everyKeyExistsInBothLanguages() throws IOException {
        final Set<String> english = keysOf("en");
        final Set<String> german = keysOf("de");

        final Set<String> onlyEnglish = new TreeSet<>(english);
        onlyEnglish.removeAll(german);
        final Set<String> onlyGerman = new TreeSet<>(german);
        onlyGerman.removeAll(english);

        assertEquals(Set.of(), onlyEnglish, "keys with no German translation");
        assertEquals(Set.of(), onlyGerman, "German keys with no English original");
    }

    @Test
    void everyKeyResolvesThroughMessagesInBothLanguages() throws IOException {
        for (final String key : keysOf("en")) {
            for (final Locale locale : new Locale[] {Locale.ENGLISH, Locale.GERMAN}) {
                assertTrue(messages.hasTranslation(locale, key), key + " does not resolve in " + locale);
            }
        }
    }

    @Test
    void thePlaceholdersOfATranslationMatchItsOriginal() throws IOException {
        final Properties english = load("en");
        final Properties german = load("de");

        for (final String key : english.stringPropertyNames()) {
            assertEquals(
                    placeholders(english.getProperty(key)),
                    placeholders(german.getProperty(key)),
                    key + " uses different placeholders in the two languages - one of them will "
                            + "print a literal {name} to a player");
        }
    }

    /**
     * Checks the {@code <_name>} component slots, which MiniMessage renders as nothing when a translation drops one.
     */
    @Test
    void theComponentSlotsOfATranslationMatchItsOriginal() throws IOException {
        final Properties english = load("en");
        final Properties german = load("de");

        for (final String key : english.stringPropertyNames()) {
            assertEquals(
                    slots(english.getProperty(key)),
                    slots(german.getProperty(key)),
                    key + " uses different <_component> slots in the two languages - an unresolved"
                            + " slot renders as nothing at all, in silence");
        }
    }

    private static Set<String> slots(final String text) {
        final Set<String> found = new TreeSet<>();
        final java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("<(_[a-zA-Z0-9_-]+)>").matcher(text);
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return found;
    }

    private static Set<String> placeholders(final String text) {
        final Set<String> found = new TreeSet<>();
        final java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("\\{([a-zA-Z0-9_-]+)}").matcher(text);
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return found;
    }

    private static Set<String> keysOf(final String language) throws IOException {
        return new TreeSet<>(load(language).stringPropertyNames());
    }

    private static Properties load(final String language) throws IOException {
        final Properties properties = new Properties();
        try (InputStream stream =
                MessageBundlesTest.class.getClassLoader().getResourceAsStream(ROOT + "/" + language + ".properties")) {
            assertNotNull(stream, "no " + language + ".properties on the test classpath");
            properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        }
        return properties;
    }
}
