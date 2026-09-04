package eu.nordtal.s2.common.message;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The operator's message override: {@code plugins/&lt;name&gt;/messages/&lt;lang&gt;.properties} on
 * top of the bundle in the jar.
 *
 * <p><b>The case that matters is the second one.</b> A whole-file override is the obvious
 * implementation and the wrong one: it freezes the wording at the moment somebody copied the file,
 * so every key a later release adds is missing and reaches the player as the literal key. Merging
 * key by key is what makes an override survive an update, and {@code aKeyTheOverrideDoesNotName}
 * is the test that says so.</p>
 */
class MessageOverridesTest {

    private static final Locale GERMAN = Locale.GERMAN;

    @Test
    @DisplayName("an override wins over the packaged bundle")
    void anOverrideWins(@TempDir final Path directory) throws IOException {
        write(directory, "de", "greeting=Moin {name}!");
        final Messages messages = load(directory);

        assertEquals("Moin Till!", messages.format(GERMAN, "greeting", "name", "Till"));
    }

    @Test
    @DisplayName("a key the override does not name still comes from the jar")
    void aKeyTheOverrideDoesNotName(@TempDir final Path directory) throws IOException {
        write(directory, "de", "greeting=Moin {name}!");
        final Messages messages = load(directory);

        assertEquals("Keine Parameter hier.", messages.get(GERMAN, "plain"),
                "the merge is per key: an override naming one line must not blank out the rest,"
                        + " or every message added by a later release reaches the player as its key");
    }

    @Test
    @DisplayName("English keeps working as the fallback under an override")
    void englishStaysTheFallback(@TempDir final Path directory) throws IOException {
        write(directory, "de", "greeting=Moin {name}!");
        final Messages messages = load(directory);

        assertEquals("This key exists in English only.", messages.get(GERMAN, "only-english"));
    }

    @Test
    @DisplayName("an override for a key nothing declares is reported by name")
    void anUnknownOverrideKeyIsReported(@TempDir final Path directory) throws IOException {
        write(directory, "de", "greting=Moin!\ngreeting=Moin {name}!");
        final Messages messages = load(directory);

        assertEquals(java.util.Set.of("de/greting"), messages.unknownOverrideKeys(),
                "an override that overrides nothing is stored and never looked up - silent unless"
                        + " something names it, which is what makes a typo here expensive");
    }

    @Test
    @DisplayName("reload picks up an edit without a new Messages")
    void reloadPicksUpAnEdit(@TempDir final Path directory) throws IOException {
        write(directory, "de", "plain=Erste Fassung.");
        final Messages messages = load(directory);
        assertEquals("Erste Fassung.", messages.get(GERMAN, "plain"));

        write(directory, "de", "plain=Zweite Fassung.");
        messages.reload();

        assertEquals("Zweite Fassung.", messages.get(GERMAN, "plain"),
                "every listener, HUD and command holds the same Messages from startup - a reload"
                        + " that produced a new instance would reach none of them");
    }

    @Test
    @DisplayName("a deleted override falls back to the jar again")
    void aDeletedOverrideFallsBack(@TempDir final Path directory) throws IOException {
        write(directory, "de", "plain=Eigene Fassung.");
        final Messages messages = load(directory);
        assertEquals("Eigene Fassung.", messages.get(GERMAN, "plain"));

        Files.delete(directory.resolve("de.properties"));
        messages.reload();

        assertEquals("Keine Parameter hier.", messages.get(GERMAN, "plain"));
    }

    @Test
    @DisplayName("the directory and its note are written once and never rewritten")
    void theNoteIsWrittenOnce(@TempDir final Path parent) throws IOException {
        final Path directory = parent.resolve("messages");
        load(directory);

        final Path readme = directory.resolve("README.txt");
        assertTrue(Files.isRegularFile(readme), "an empty folder in a data directory teaches"
                + " nobody what it is for");
        assertTrue(Files.readString(readme).contains("messages/test"),
                "the note has to name the bundle it overrides");

        Files.writeString(readme, "the operator wrote their own note here");
        load(directory);
        assertEquals("the operator wrote their own note here", Files.readString(readme),
                "rewriting a file somebody edited is how a data folder stops being theirs");
    }

    @Test
    @DisplayName("umlauts survive the override, which is read as UTF-8 and not as ISO-8859-1")
    void umlautsSurvive(@TempDir final Path directory) throws IOException {
        write(directory, "de", "plain=Grüße aus Nordtal - schöne Größe.");
        final Messages messages = load(directory);

        assertEquals("Grüße aus Nordtal - schöne Größe.", messages.get(GERMAN, "plain"),
                "Properties.load(InputStream) is ISO-8859-1 - the override path has to read the"
                        + " file the same way the packaged bundle is read, or an operator's umlaut"
                        + " becomes mojibake on screen");
    }

    @Test
    @DisplayName("no override directory behaves exactly as before")
    void withoutAnOverrideNothingChanges() {
        final Messages messages = Messages.load(
                MessageOverridesTest.class.getClassLoader(), "messages/test", GERMAN);

        assertEquals("Hallo Till!", messages.format(GERMAN, "greeting", "name", "Till"));
        assertTrue(messages.overrideDirectory().isEmpty());
        assertFalse(messages.unknownOverrideKeys().iterator().hasNext());
    }

    private static Messages load(final Path directory) {
        return Messages.load(MessageOverridesTest.class.getClassLoader(), "messages/test",
                directory, GERMAN);
    }

    private static void write(final Path directory, final String language, final String content)
            throws IOException {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(language + ".properties"), content,
                StandardCharsets.UTF_8);
    }
}
