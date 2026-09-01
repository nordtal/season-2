package eu.nordtal.s2.hungergames;

import eu.nordtal.s2.common.message.Messages;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That the two language files stay the same file in two languages.
 *
 * <p>Added 2026-09-01, after a sweep found nine keys in this module that existed in both languages
 * and were referenced by no line of code: a silent countdown, an unannounced demotion, two
 * unreachable tiebreak sentences, a duplicate of the winner line, a command guard Brigadier makes
 * impossible to reach, and a database-error text with no catch block anywhere near it. Three were
 * deleted, three were wired up, and two are annotated in the files as deliberately unsent.
 *
 * <p>The half of that a test can hold onto is <b>symmetry</b>: a key added to one file and not the
 * other is a player seeing the literal string {@code hg.start.countdown} in the middle of a game,
 * because {@code Messages} degrades to the key rather than throwing - which is the right runtime
 * behaviour and exactly why it has to fail here instead. Whether a key is <em>used</em> cannot be
 * asserted from inside the module; that stays a review job, and the two keys that are knowingly
 * unused say so in a comment beside them.</p>
 */
class MessageBundlesTest {

    private static final String ROOT = "messages/hunger-games";

    private final Messages messages = Messages.load(MessageBundlesTest.class.getClassLoader(),
            ROOT, Locale.ENGLISH, Locale.GERMAN);

    @Test
    void bothBundlesAreLoaded() {
        assertTrue(messages.languages().contains("en"));
        assertTrue(messages.languages().contains("de"),
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
            for (final Locale locale : new Locale[]{Locale.ENGLISH, Locale.GERMAN}) {
                assertTrue(messages.hasTranslation(locale, key), key + " does not resolve in " + locale);
            }
        }
    }

    @Test
    void thePlaceholdersOfATranslationMatchItsOriginal() throws IOException {
        final Properties english = load("en");
        final Properties german = load("de");

        for (final String key : english.stringPropertyNames()) {
            assertEquals(placeholders(english.getProperty(key)), placeholders(german.getProperty(key)),
                    key + " uses different placeholders in the two languages - one of them will "
                            + "print a literal {name} to a player");
        }
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
        try (InputStream stream = MessageBundlesTest.class.getClassLoader()
                .getResourceAsStream(ROOT + "/" + language + ".properties")) {
            assertNotNull(stream, "no " + language + ".properties on the test classpath");
            properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        }
        return properties;
    }
}
