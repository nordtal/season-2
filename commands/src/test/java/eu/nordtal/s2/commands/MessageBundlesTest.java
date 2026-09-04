package eu.nordtal.s2.commands;

import eu.nordtal.s2.common.message.Messages;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shared bundle: the same file in two languages, complete, and free of markup.
 *
 * <p>The first two checks are the ones {@code smp} and {@code hunger-games} already have, for the
 * reason they give - {@code Messages} degrades to the key rather than throwing, so a key present in
 * one language and absent in the other reaches somebody as the literal string {@code phase.failed}.
 *
 * <p>The other two exist only here, because only this bundle is read by more than one process:</p>
 * <ul>
 *   <li><b>Every key a shared command asks for exists.</b> A command holds no bundle - it names
 *       keys - so a typo in one is invisible until somebody runs that branch, and the branches that
 *       matter here are the failure ones nobody runs on purpose.</li>
 *   <li><b>No markup.</b> These strings are rendered as MiniMessage on Minecraft and as Discord
 *       markdown in the guild; either one's syntax is literal text on the other surface.</li>
 * </ul>
 */
class MessageBundlesTest {

    private static final String ROOT = "messages/commands";

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
            assertEquals(placeholders(english.getProperty(key)), placeholders(german.getProperty(key)),
                    key + " uses different placeholders in the two languages - one of them will"
                            + " print a literal {name} to somebody");
        }
    }

    @Test
    @DisplayName("every key a shared command names actually exists, in both languages")
    void noCommandNamesAKeyThatIsNotThere() throws IOException {
        final Set<String> declared = keysOf("en");
        final List<String> missing = new ArrayList<>();

        // Literals only: a key built by concatenation cannot be checked this way, and there is
        // exactly one - SetPhase#consequenceKey, whose five results PhaseCommandsTest walks against
        // SeasonPhase.values() instead.
        final Pattern named = Pattern.compile("(?:reply|phrase)\\(\\s*\"([a-z][a-z0-9.-]*)\"");
        for (final Path source : sources()) {
            final Matcher matcher = named.matcher(Files.readString(source, StandardCharsets.UTF_8));
            while (matcher.find()) {
                if (!declared.contains(matcher.group(1))) {
                    missing.add(source.getFileName() + ": " + matcher.group(1));
                }
            }
        }

        assertEquals(List.of(), missing,
                "a command named a message key no bundle declares. Messages answers the key itself,"
                        + " so this reaches somebody as the literal string phase.date.failed at the"
                        + " moment a date failed to write.");
        for (final String key : declared) {
            assertTrue(messages.hasTranslation(Locale.GERMAN, key), key + " does not resolve in de");
        }
    }

    @Test
    @DisplayName("the shared bundle carries no markup, because it is rendered on two surfaces")
    void neitherSurfacesSyntaxLeaksIntoTheOther() throws IOException {
        // MiniMessage tags would print as <b>...</b> in Discord; Discord's ** would print as
        // asterisks in chat. The rule is written at the top of en.properties; this is what makes it
        // true rather than aspirational.
        final List<String> offending = new ArrayList<>();
        for (final String language : List.of("en", "de")) {
            final Properties bundle = load(language);
            for (final String key : bundle.stringPropertyNames()) {
                final String value = bundle.getProperty(key);
                if (value.contains("**") || value.contains("`")) {
                    offending.add(language + "/" + key + ": Discord markdown");
                }
                if (Pattern.compile("</?[a-z_][a-z0-9_:.#-]*>").matcher(value).find()) {
                    offending.add(language + "/" + key + ": a MiniMessage tag");
                }
            }
        }
        assertEquals(List.of(), offending, "see the header of messages/commands/en.properties");
    }

    @Test
    @DisplayName("the consequence sentences say the thing the confirmation exists for")
    void theConsequencesNameWhatActuallyHappens() throws IOException {
        // Content assertions, which are usually brittle and are worth it here: this is the only
        // reason there is a confirmation step at all. docs/season-phases.md#routing settles that a
        // switch to SMP disconnects a player with no active access rather than moving them to
        // limbo, and an admin has to read that before clicking, in either language.
        final Properties english = load("en");
        final Properties german = load("de");

        assertTrue(english.getProperty("phase.consequence.SMP").contains("disconnected"),
                english.getProperty("phase.consequence.SMP"));
        assertTrue(german.getProperty("phase.consequence.SMP").contains("getrennt"),
                german.getProperty("phase.consequence.SMP"));

        assertTrue(english.getProperty("phase.consequence.MAINTENANCE").contains("admins"),
                english.getProperty("phase.consequence.MAINTENANCE"));
        assertTrue(german.getProperty("phase.consequence.MAINTENANCE").contains("Admins"),
                german.getProperty("phase.consequence.MAINTENANCE"));

        // Access is only required from SMP onwards. Saying otherwise would be the confirmation
        // lying about what a switch costs.
        for (final String free : List.of("phase.consequence.PRE_EVENT",
                "phase.consequence.START_EVENT")) {
            assertTrue(english.getProperty(free).contains("hunger-games"),
                    free + ": " + english.getProperty(free));
            assertTrue(!english.getProperty(free).contains("disconnected"),
                    free + " claims somebody is disconnected: " + english.getProperty(free));
        }
    }

    // ---------------------------------------------------------------- helpers

    private static Set<String> placeholders(final String template) {
        final Set<String> found = new TreeSet<>();
        final Matcher matcher = Pattern.compile("\\{([a-zA-Z]+)}").matcher(template);
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return found;
    }

    private Set<String> keysOf(final String language) throws IOException {
        return new TreeSet<>(load(language).stringPropertyNames());
    }

    private Properties load(final String language) throws IOException {
        try (InputStream stream = getClass().getClassLoader()
                .getResourceAsStream(ROOT + "/" + language + ".properties")) {
            if (stream == null) {
                throw new IOException("no " + ROOT + "/" + language + ".properties on the classpath");
            }
            final Properties properties = new Properties();
            properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
            return properties;
        }
    }

    /** Every command source in this module, anchored on the directory holding settings.gradle.kts. */
    private static List<Path> sources() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null && !Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) {
            candidate = candidate.getParent();
        }
        if (candidate == null) {
            throw new IllegalStateException("no settings.gradle.kts above the working directory");
        }
        final Path root = candidate.resolve("commands/src/main/java");
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(path -> path.toString().endsWith(".java")).sorted().toList();
        } catch (final IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
