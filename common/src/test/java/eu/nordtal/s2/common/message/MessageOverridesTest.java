package eu.nordtal.s2.common.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests the operator's message override on top of the bundle in the jar.
 *
 * The override merges key by key, so a key it does not name keeps following the jar.
 */
class MessageOverridesTest {

    private static final Locale GERMAN = Locale.GERMAN;

    @Test
    void anOverrideWinsOverThePackagedBundle(@TempDir final Path directory) throws IOException {
        write(directory, "de", "greeting=Moin {name}!");
        final Messages messages = load(directory);

        assertEquals("Moin Till!", messages.format(GERMAN, "greeting", "name", "Till"));
    }

    @Test
    void aKeyTheOverrideDoesNotNameStillComesFromTheJar(@TempDir final Path directory) throws IOException {
        write(directory, "de", "greeting=Moin {name}!");
        final Messages messages = load(directory);

        assertEquals(
                "Keine Parameter hier.",
                messages.get(GERMAN, "plain"),
                "the merge is per key: an override naming one line must not blank out the rest,"
                        + " or every message added by a later release reaches the player as its key");
    }

    @Test
    void englishKeepsWorkingAsTheFallbackUnderAnOverride(@TempDir final Path directory) throws IOException {
        write(directory, "de", "greeting=Moin {name}!");
        final Messages messages = load(directory);

        assertEquals("This key exists in English only.", messages.get(GERMAN, "only-english"));
    }

    @Test
    void anOverrideForAKeyNothingDeclaresIsReportedByName(@TempDir final Path directory) throws IOException {
        write(directory, "de", "greting=Moin!\ngreeting=Moin {name}!");
        final Messages messages = load(directory);

        assertEquals(
                java.util.Set.of("de/greting"),
                messages.unknownOverrideKeys(),
                "an override that overrides nothing is stored and never looked up - silent unless"
                        + " something names it, which is what makes a typo here expensive");
    }

    @Test
    void overridingAKeyOnlyEnglishDeclaresWorksAndIsNotReportedAsATypo(@TempDir final Path directory)
            throws IOException {
        write(directory, "de", "only-english=Diesen Schlüssel gibt es nur auf Englisch.");
        final Messages messages = load(directory);

        assertEquals(
                "Diesen Schlüssel gibt es nur auf Englisch.",
                messages.get(GERMAN, "only-english"),
                "the override is merged into the German bundle and get(GERMAN, ...) returns it");
        assertEquals(
                java.util.Set.of(),
                messages.unknownOverrideKeys(),
                "this override demonstrably works, so calling it a key 'no bundle declares' sends"
                        + " the operator hunting for a spelling mistake in a line they can watch"
                        + " taking effect - the question has to be asked of every packaged bundle,"
                        + " not of the one language's");
    }

    @Test
    void reloadPicksUpAnEditWithoutANewMessages(@TempDir final Path directory) throws IOException {
        write(directory, "de", "plain=Erste Fassung.");
        final Messages messages = load(directory);
        assertEquals("Erste Fassung.", messages.get(GERMAN, "plain"));

        write(directory, "de", "plain=Zweite Fassung.");
        messages.reload();

        assertEquals(
                "Zweite Fassung.",
                messages.get(GERMAN, "plain"),
                "every listener, HUD and command holds the same Messages from startup - a reload"
                        + " that produced a new instance would reach none of them");
    }

    @Test
    void aDeletedOverrideFallsBackToTheJarAgain(@TempDir final Path directory) throws IOException {
        write(directory, "de", "plain=Eigene Fassung.");
        final Messages messages = load(directory);
        assertEquals("Eigene Fassung.", messages.get(GERMAN, "plain"));

        Files.delete(directory.resolve("de.properties"));
        messages.reload();

        assertEquals("Keine Parameter hier.", messages.get(GERMAN, "plain"));
    }

    @Test
    void theDirectoryAndItsNoteAreWrittenOnceAndNeverRewritten(@TempDir final Path parent) throws IOException {
        final Path directory = parent.resolve("messages");
        load(directory);

        final Path readme = directory.resolve("README.txt");
        assertTrue(
                Files.isRegularFile(readme), "an empty folder in a data directory teaches" + " nobody what it is for");
        assertTrue(Files.readString(readme).contains("messages/test"), "the note has to name the bundle it overrides");

        Files.writeString(readme, "the operator wrote their own note here");
        load(directory);
        assertEquals(
                "the operator wrote their own note here",
                Files.readString(readme),
                "rewriting a file somebody edited is how a data folder stops being theirs");
    }

    @Test
    void umlautsSurviveTheOverrideWhichIsReadAsUtf8AndNotAsIso88591(@TempDir final Path directory) throws IOException {
        write(directory, "de", "plain=Grüße aus Nordtal - schöne Größe.");
        final Messages messages = load(directory);

        assertEquals(
                "Grüße aus Nordtal - schöne Größe.",
                messages.get(GERMAN, "plain"),
                "Properties.load(InputStream) is ISO-8859-1 - the override path has to read the"
                        + " file the same way the packaged bundle is read, or an operator's umlaut"
                        + " becomes mojibake on screen");
    }

    @Test
    void noOverrideDirectoryBehavesExactlyAsBefore() {
        final Messages messages = Messages.load(MessageOverridesTest.class.getClassLoader(), "messages/test", GERMAN);

        assertEquals("Hallo Till!", messages.format(GERMAN, "greeting", "name", "Till"));
        assertTrue(messages.overrideDirectory().isEmpty());
        assertFalse(messages.unknownOverrideKeys().iterator().hasNext());
    }

    private static Messages load(final Path directory) {
        return Messages.load(MessageOverridesTest.class.getClassLoader(), "messages/test", directory, GERMAN);
    }

    private static void write(final Path directory, final String language, final String content) throws IOException {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(language + ".properties"), content, StandardCharsets.UTF_8);
    }
}
