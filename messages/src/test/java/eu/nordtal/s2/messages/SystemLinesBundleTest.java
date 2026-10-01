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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Checks that paper-common's bundle carries every line {@code SystemLines} names, alike in both languages.
 *
 * That both servers where players see each other have the lines is {@code :architecture}'s rule.
 */
class SystemLinesBundleTest {

    private static final String BUNDLE = "paper-common/src/main/resources/messages/paper-common";

    /** Every key {@code SystemLines} names. A missing one reaches a player as the key itself. */
    private static final List<String> KEYS =
            List.of("system.chat.line", "system.join", "system.leave", "system.death", "system.advancement");

    /** {@code {name}}: a value substituted before the MiniMessage is parsed, and escaped. */
    private static final Pattern PARAMETER = Pattern.compile("\\{([a-zA-Z][a-zA-Z0-9]*)}");

    /** {@code <_name>}: a slot for something that is already a component. */
    private static final Pattern SLOT = Pattern.compile("<(_[a-zA-Z][a-zA-Z0-9]*)>");

    @Test
    void everyKeySystemlinesNamesExistsInBothLanguages() {
        final Properties english = load("en");
        final Properties german = load("de");
        for (final String key : KEYS) {
            assertTrue(english.containsKey(key), "messages/paper-common/en.properties has no " + key);
            assertTrue(german.containsKey(key), "messages/paper-common/de.properties has no " + key);
        }
        assertEquals(
                new TreeSet<>(english.stringPropertyNames()),
                new TreeSet<>(german.stringPropertyNames()),
                "the two languages declare different keys, so one of them reaches somebody as the" + " key itself");
    }

    @Test
    void bothLanguagesCarryTheSameParametersAndTheSameComponentSlots() {
        final Properties english = load("en");
        final Properties german = load("de");
        final List<String> wrong = new ArrayList<>();
        for (final String key : english.stringPropertyNames()) {
            final Map<String, Set<String>> left = holes(english.getProperty(key));
            final Map<String, Set<String>> right = holes(german.getProperty(key));
            if (!left.equals(right)) {
                // An unresolved <_slot> renders as nothing, so slots are checked separately from parameters.
                wrong.add(key + ": en " + left + " vs de " + right);
            }
        }
        assertEquals(
                List.of(),
                wrong,
                "a parameter or a component slot in one language and not the other is either a"
                        + " printed {placeholder} or, for a slot, silence where a name should be");
    }

    private static Map<String, Set<String>> holes(final String template) {
        return Map.of("parameters", found(PARAMETER, template), "slots", found(SLOT, template));
    }

    private static Set<String> found(final Pattern pattern, final String template) {
        final Set<String> names = new TreeSet<>();
        final Matcher matcher = pattern.matcher(template);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    private static Properties load(final String language) {
        final Properties properties = new Properties();
        final Path file = RepositoryRoot.resolve(BUNDLE + "/" + language + ".properties");
        try (Reader reader = new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
        return properties;
    }
}
