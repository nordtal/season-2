package eu.nordtal.s2.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * This plugin's bundle: the same file in two languages, and complete in both.
 *
 * <b>English is the fallback for everything</b>, so a key with no German is answered in
 * English: nothing throws, nothing is logged, and a German player gets one English line in the
 * middle of German text. That is the quiet failure, which is why it needs a test; a key missing in
 * English too is the louder one and prints the key itself.
 *
 * The parity question is also asked repository-wide, once, by {@code EveryBundleIsCompleteTest}
 * in {@code :common}. This test stays because the pass below it is a different question: the
 * other module's version also walks the command classes to check that every key <i>named in
 * code</i> exists, which is not reproduced here, because this plugin reaches its bundle through
 * {@code GateMessages} and {@code PackMessages} rather than from the command classes directly.
 */
class MessageBundlesTest {

    private static final String ROOT = "messages/proxy";

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
    void thePlaceholdersOfATranslationMatchItsOriginal() throws IOException {
        final Properties english = load("en");
        final Properties german = load("de");

        for (final String key : english.stringPropertyNames()) {
            if (german.getProperty(key) == null) {
                continue; // everyKeyExistsInBothLanguages says this, and says it better
            }
            assertEquals(
                    placeholders(english.getProperty(key)),
                    placeholders(german.getProperty(key)),
                    key + " uses different placeholders in the two languages - one of them will"
                            + " print a literal {name} to somebody");
        }
    }

    private static Set<String> keysOf(final String language) throws IOException {
        return new TreeSet<>(load(language).stringPropertyNames());
    }

    private static Properties load(final String language) throws IOException {
        final Properties properties = new Properties();
        try (InputStream stream =
                MessageBundlesTest.class.getClassLoader().getResourceAsStream(ROOT + "/" + language + ".properties")) {
            assertTrue(stream != null, ROOT + "/" + language + ".properties is not on the classpath");
            properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        }
        return properties;
    }

    private static Set<String> placeholders(final String text) {
        final Set<String> names = new TreeSet<>();
        final Matcher matcher = Pattern.compile("\\{([a-z0-9_-]+)}").matcher(text);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }
}
