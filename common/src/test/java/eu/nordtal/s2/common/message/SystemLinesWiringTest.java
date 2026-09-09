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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That both Paper servers with players on them actually write the five system lines.
 *
 * <h2>The failure this exists for, which lasted the whole of the build</h2>
 * {@code SystemLines} was written for {@code smp} and lived in {@code smp}, so the hunger games -
 * the season's flagship event, the one every player on the network attends at the same moment - had
 * vanilla chat, vanilla join and leave and vanilla death messages: yellow, in the server's language
 * rather than the reader's, with no flag on anybody (finding 149). Nothing could see it. Both
 * modules compiled, both had complete bundles, both had green tests, and the difference was one
 * class one of them did not construct.
 *
 * <p>That is exactly the shape {@code AdminWatchWiringTest} was written for and for the same
 * reason: a mechanism that exists, is tested, and has no caller looks identical to one that works.
 * So this is a text search over the two plugins' main classes, like that one.</p>
 *
 * <h2>What it deliberately does not check</h2>
 * That the lines look right. Nothing in a JVM with no server in it can: a chat renderer is called by
 * Paper per recipient, a death message is a component off a packet, and an icon is a code point in a
 * font. What a rehearsal has to answer is in the report and in the owner's checklist.
 */
class SystemLinesWiringTest {

    /**
     * The two servers a player stands on and talks on, and the class each has to build.
     *
     * <p>{@code limbo} is deliberately not here: nobody speaks there, everybody is hidden from
     * everybody else, and the whole interface is one title on a black screen. A join line in the
     * waiting room would be a line about a player nobody can see, addressed to nobody.</p>
     */
    private static final List<String> PLUGINS = List.of(
            "smp/src/main/java/eu/nordtal/s2/smp/SmpPlugin.java",
            "hunger-games/src/main/java/eu/nordtal/s2/hungergames/HungerGamesPlugin.java");

    private static final String BUNDLE = "paper-common/src/main/resources/messages/paper-common";

    /** Every key {@code SystemLines} names. A missing one reaches a player as the key itself. */
    private static final List<String> KEYS = List.of(
            "system.chat.line", "system.join", "system.leave", "system.death",
            "system.advancement");

    /** {@code {name}} - a value substituted before the MiniMessage is parsed, and escaped. */
    private static final Pattern PARAMETER = Pattern.compile("\\{([a-zA-Z][a-zA-Z0-9]*)}");

    /** {@code <_name>} - a slot for something that is already a component. */
    private static final Pattern SLOT = Pattern.compile("<(_[a-zA-Z][a-zA-Z0-9]*)>");

    @Test
    @DisplayName("both Paper servers with players on them build and register SystemLines")
    void bothServersWriteTheirOwnLines() {
        final List<String> missing = new ArrayList<>();
        for (final String plugin : PLUGINS) {
            final String source = read(RepositoryRoot.resolve(plugin));
            if (!source.contains("new SystemLines(")) {
                missing.add(plugin + " never builds SystemLines, so chat, join, leave, death and"
                        + " advancement on that server are vanilla's - one language, no flag, and"
                        + " yellow. That is finding 149 exactly.");
                continue;
            }
            if (!source.contains("registerEvents(systemLines")) {
                missing.add(plugin + " builds SystemLines and never registers it as a listener,"
                        + " which is the same thing as not having it and looks like having it.");
            }
        }
        assertEquals(List.of(), missing);
    }

    @Test
    @DisplayName("both Paper servers load the shared bundle those lines are written in")
    void bothServersLoadTheBundle() {
        final List<String> missing = new ArrayList<>();
        for (final String plugin : PLUGINS) {
            if (!read(RepositoryRoot.resolve(plugin)).contains("\"messages/paper-common\"")) {
                missing.add(plugin + " does not load messages/paper-common, so every one of the"
                        + " five lines reaches a player as the literal key - Messages degrades to"
                        + " the key rather than throwing, so it fails silently and only in chat.");
            }
        }
        assertEquals(List.of(), missing);
    }

    @Test
    @DisplayName("every key SystemLines names exists in both languages")
    void everyKeyIsTranslated() {
        final Properties english = load("en");
        final Properties german = load("de");
        for (final String key : KEYS) {
            assertTrue(english.containsKey(key), "messages/paper-common/en.properties has no " + key);
            assertTrue(german.containsKey(key), "messages/paper-common/de.properties has no " + key);
        }
        assertEquals(new TreeSet<>(english.stringPropertyNames()),
                new TreeSet<>(german.stringPropertyNames()),
                "the two languages declare different keys, so one of them reaches somebody as the"
                        + " key itself");
    }

    @Test
    @DisplayName("both languages carry the same parameters and the same component slots")
    void bothLanguagesFillTheSameHoles() {
        final Properties english = load("en");
        final Properties german = load("de");
        final List<String> wrong = new ArrayList<>();
        for (final String key : english.stringPropertyNames()) {
            final Map<String, Set<String>> left = holes(english.getProperty(key));
            final Map<String, Set<String>> right = holes(german.getProperty(key));
            if (!left.equals(right)) {
                // An unresolved <_slot> renders as NOTHING AT ALL, in silence - which is why the
                // slots are checked separately from the parameters rather than by counting braces.
                wrong.add(key + ": en " + left + " vs de " + right);
            }
        }
        assertEquals(List.of(), wrong,
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
        try (Reader reader = new InputStreamReader(Files.newInputStream(file),
                StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
        return properties;
    }

    private static String read(final Path source) {
        assertTrue(Files.isRegularFile(source), source + " no longer exists - a missing file is a"
                + " check that silently stops running");
        try {
            return Files.readString(source, StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot read " + source, e);
        }
    }
}
