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
 * Checks that both Paper servers with players on them build {@code SystemLines} and register it.
 *
 * A text search over the plugins' main classes, since a mechanism with no caller looks identical to one that works.
 */
class SystemLinesWiringTest {

    // The listener may sit on a holder record, e.g. presence.systemLines().
    private static final Pattern REGISTERS_SYSTEM_LINES =
            Pattern.compile("(?:registerEvents|listen)\\((?:\\w+\\.)?systemLines\\b");

    /** The servers a player talks on, and the class each builds; limbo is absent, as nobody there is visible. */
    private static final List<String> PLUGINS = List.of(
            "smp/src/main/java/eu/nordtal/s2/smp/SmpPlugin.java",
            "hunger-games/src/main/java/eu/nordtal/s2/hungergames/HungerGamesPlugin.java");

    private static final String BUNDLE = "paper-common/src/main/resources/messages/paper-common";

    /** Every key {@code SystemLines} names. A missing one reaches a player as the key itself. */
    private static final List<String> KEYS =
            List.of("system.chat.line", "system.join", "system.leave", "system.death", "system.advancement");

    /** {@code {name}}: a value substituted before the MiniMessage is parsed, and escaped. */
    private static final Pattern PARAMETER = Pattern.compile("\\{([a-zA-Z][a-zA-Z0-9]*)}");

    /** {@code <_name>}: a slot for something that is already a component. */
    private static final Pattern SLOT = Pattern.compile("<(_[a-zA-Z][a-zA-Z0-9]*)>");

    @Test
    void bothPaperServersWithPlayersOnThemBuildAndRegisterSystemlines() {
        final List<String> missing = new ArrayList<>();
        for (final String plugin : PLUGINS) {
            final String source = read(RepositoryRoot.resolve(plugin));
            if (!source.contains("new SystemLines(")) {
                missing.add(plugin + " never builds SystemLines, so chat, join, leave, death and"
                        + " advancement on that server are vanilla's - one language, no flag, and"
                        + " yellow. That is finding 149 exactly.");
                continue;
            }
            if (!REGISTERS_SYSTEM_LINES.matcher(source).find()) {
                missing.add(plugin + " builds SystemLines and never registers it as a listener,"
                        + " which is the same thing as not having it and looks like having it.");
            }
        }
        assertEquals(List.of(), missing);
    }

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

    private static String read(final Path source) {
        assertTrue(
                Files.isRegularFile(source),
                source + " is missing, and a missing file is a check that silently stops running");
        try {
            // A plugin may delegate its start to a sibling <Name>Start.java; the wiring is read from both.
            final Path start =
                    source.resolveSibling(source.getFileName().toString().replace("Plugin.java", "Start.java"));
            final String own = Files.readString(source, StandardCharsets.UTF_8);
            return Files.isRegularFile(start) && !start.equals(source)
                    ? own + "\n" + Files.readString(start, StandardCharsets.UTF_8)
                    : own;
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot read " + source, e);
        }
    }
}
