package eu.nordtal.s2.networkcontrol;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This plugin's bundle: the same file in two languages, and complete in both.
 *
 * <p>The three other modules that ship a bundle - {@code commands}, {@code smp} and
 * {@code hunger-games} - have had this guard for some time; {@code network-control} did not, which
 * is why {@code gate.connection-lost} was added on 2026-09-16 (season-2-ops/20) with nothing
 * checking that the German half arrived with it. {@code Messages} degrades to the key rather than
 * throwing, so a missing translation is not an exception anybody sees - it is the literal string
 * {@code gate.connection-lost} on a disconnect screen, in the language it was supposed to avoid.</p>
 *
 * <p>This is the parity half only. The other module's version also walks the command classes to
 * check that every key <i>named in code</i> exists; that pass is not reproduced here, because this
 * plugin reaches its bundle through {@code GateMessages} and {@code PackMessages} rather than from
 * the command classes directly.</p>
 */
class MessageBundlesTest {

    private static final String ROOT = "messages/network-control";

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
    void thePlaceholdersOfATranslationMatchItsOriginal() throws IOException {
        final Properties english = load("en");
        final Properties german = load("de");

        for (final String key : english.stringPropertyNames()) {
            if (german.getProperty(key) == null) {
                continue; // everyKeyExistsInBothLanguages says this, and says it better
            }
            assertEquals(placeholders(english.getProperty(key)), placeholders(german.getProperty(key)),
                    key + " uses different placeholders in the two languages - one of them will"
                            + " print a literal {name} to somebody");
        }
    }

    private static Set<String> keysOf(final String language) throws IOException {
        return new TreeSet<>(load(language).stringPropertyNames());
    }

    private static Properties load(final String language) throws IOException {
        final Properties properties = new Properties();
        try (InputStream stream = MessageBundlesTest.class.getClassLoader()
                .getResourceAsStream(ROOT + "/" + language + ".properties")) {
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
