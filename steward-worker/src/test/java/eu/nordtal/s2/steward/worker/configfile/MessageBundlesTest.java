package eu.nordtal.s2.steward.worker.configfile;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bundles a module ships in its jar, merged with an operator's override (steward/48).
 *
 * <p>Fixture jars are built with real {@link JarOutputStream} entries rather than fixtures loaded
 * off the test's own classpath - this class opens an arbitrary path as a zip, and the point being
 * tested is exactly that opening, so a fixture that only ever lived on the classpath would not be
 * testing the same code path a real deployment exercises.</p>
 */
class MessageBundlesTest {

    @TempDir
    Path configs;

    @TempDir
    Path volumes;

    // ---------------------------------------------------------------------------------------
    // Discovery
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("a Paper plugin's bundle is found next to its own jar, in the configs mount")
    void aPluginBundleIsFoundBesideItsOwnJar() throws IOException {
        writeJar(configs.resolve("smp/smp-0.9.1.jar"), Map.of(
                "messages/smp/en.properties", "welcome=Welcome\n"));
        Files.createDirectories(configs.resolve("smp/smp/messages"));

        final List<MessageBundleLocation> found = MessageBundles.discover(configs, volumes);

        assertEquals(1, found.size(), found.toString());
        assertEquals("smp", found.getFirst().service());
        assertEquals("smp", found.getFirst().module());
        assertEquals(configs.resolve("smp/smp-0.9.1.jar"), found.getFirst().jar());
    }

    @Test
    @DisplayName("a standalone jar's bundle has no module, and its jar is found in the volumes mount")
    void aStandaloneJarIsFoundInTheVolumesMount() throws IOException {
        // discord-bot's own jar is not under the configs mount at all - only its data is.
        Files.createDirectories(configs.resolve("discord-bot/messages"));
        writeJar(volumes.resolve("discord-bot/discord-bot-0.9.1.jar"), Map.of(
                "messages/access/en.properties", "contribution.title=Access\n"));

        final List<MessageBundleLocation> found = MessageBundles.discover(configs, volumes);

        assertEquals(1, found.size(), found.toString());
        assertEquals("discord-bot", found.getFirst().service());
        assertEquals("", found.getFirst().module());
        assertEquals(volumes.resolve("discord-bot/discord-bot-0.9.1.jar"), found.getFirst().jar());
    }

    @Test
    @DisplayName("a messages directory with no jar to match it is skipped, not reported broken")
    void aBundleWithNoMatchingJarIsSkipped() throws IOException {
        Files.createDirectories(configs.resolve("limbo/limbo/messages"));
        // No jar anywhere - a deployment mid-way through installing this module for the first time.

        assertEquals(List.of(), MessageBundles.discover(configs, volumes));
    }

    @Test
    @DisplayName("a root that is not mounted is an empty list, not a failure")
    void anUnmountedRootIsEmpty() {
        assertEquals(List.of(), MessageBundles.discover(configs.resolve("never-mounted"), volumes));
    }

    // ---------------------------------------------------------------------------------------
    // Reading and merging
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("several roots in one jar are merged into one bundle, the way Messages.load merges them")
    void severalRootsAreMergedIntoOneBundle() throws IOException {
        writeJar(configs.resolve("smp/smp-0.9.1.jar"), Map.of(
                "messages/commands/en.properties", "reload.done=Reloaded\n",
                "messages/commands/de.properties", "reload.done=Neu geladen\n",
                "messages/smp/en.properties", "welcome=Welcome\n",
                "messages/smp/de.properties", "welcome=Willkommen\n"));
        final Path overrides = Files.createDirectories(configs.resolve("smp/smp/messages"));
        final MessageBundleLocation location = new MessageBundleLocation(
                "smp", "smp", configs.resolve("smp/smp-0.9.1.jar"), overrides, true);

        final MessageBundle bundle = MessageBundles.read(location);

        assertEquals(List.of("reload.done", "welcome"),
                bundle.entries().stream().map(MessageEntry::key).sorted().toList());
        final MessageEntry welcome = entry(bundle, "welcome");
        assertEquals("Welcome", welcome.english());
        assertEquals("Willkommen", welcome.german());
        assertTrue(welcome.inBundle());
    }

    @Test
    @DisplayName("a key missing from the German bundle is a real gap, not silently filled with English")
    void aKeyMissingFromGermanIsReportedAsMissing() throws IOException {
        writeJar(configs.resolve("smp/smp-0.9.1.jar"), Map.of(
                "messages/smp/en.properties", "new-feature=New!\n",
                "messages/smp/de.properties", "# nothing translated yet\n"));
        final Path overrides = Files.createDirectories(configs.resolve("smp/smp/messages"));
        final MessageBundleLocation location = new MessageBundleLocation(
                "smp", "smp", configs.resolve("smp/smp-0.9.1.jar"), overrides, true);

        final MessageEntry entry = entry(MessageBundles.read(location), "new-feature");

        assertEquals("New!", entry.english());
        assertNull(entry.german());
    }

    @Test
    @DisplayName("an override is carried beside the packaged text, not merged over it")
    void anOverrideIsCarriedSeparately() throws IOException {
        writeJar(configs.resolve("smp/smp-0.9.1.jar"), Map.of(
                "messages/smp/en.properties", "welcome=Welcome\n"));
        final Path overrides = Files.createDirectories(configs.resolve("smp/smp/messages"));
        Files.writeString(overrides.resolve("en.properties"), "welcome=Howdy\n", StandardCharsets.UTF_8);
        final MessageBundleLocation location = new MessageBundleLocation(
                "smp", "smp", configs.resolve("smp/smp-0.9.1.jar"), overrides, true);

        final MessageEntry entry = entry(MessageBundles.read(location), "welcome");

        assertEquals("Welcome", entry.english(), "the packaged text must still be visible");
        assertEquals("Howdy", entry.overrideEnglish());
    }

    @Test
    @DisplayName("a key only an override names, that no bundle declares, is shown and marked as such")
    void anUnknownOverrideKeyIsShownNotHidden() throws IOException {
        writeJar(configs.resolve("smp/smp-0.9.1.jar"), Map.of(
                "messages/smp/en.properties", "welcome=Welcome\n"));
        final Path overrides = Files.createDirectories(configs.resolve("smp/smp/messages"));
        Files.writeString(overrides.resolve("en.properties"), "typo-key=oops\n", StandardCharsets.UTF_8);
        final MessageBundleLocation location = new MessageBundleLocation(
                "smp", "smp", configs.resolve("smp/smp-0.9.1.jar"), overrides, true);

        final MessageEntry entry = entry(MessageBundles.read(location), "typo-key");

        assertEquals("oops", entry.overrideEnglish());
        assertFalse(entry.inBundle());
    }

    // ---------------------------------------------------------------------------------------
    // Writing, and the encoding round trip
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("saving a line creates the override, and resetting it removes the key entirely")
    void savingCreatesAndResettingRemoves() throws IOException {
        writeJar(configs.resolve("smp/smp-0.9.1.jar"), Map.of(
                "messages/smp/en.properties", "welcome=Welcome\n"));
        final Path overrides = Files.createDirectories(configs.resolve("smp/smp/messages"));
        final MessageBundleLocation location = new MessageBundleLocation(
                "smp", "smp", configs.resolve("smp/smp-0.9.1.jar"), overrides, true);

        MessageBundles.write(location, "en", Map.of("welcome", "Howdy"));
        assertEquals("Howdy", entry(MessageBundles.read(location), "welcome").overrideEnglish());

        // Resetting deletes the key from the override - it does not copy the English text into it,
        // which would freeze the wording exactly the way a whole-file override would (steward/48).
        final Map<String, String> reset = new java.util.HashMap<>();
        reset.put("welcome", null);
        MessageBundles.write(location, "en", reset);
        assertNull(entry(MessageBundles.read(location), "welcome").overrideEnglish());
        assertFalse(Files.readString(overrides.resolve("en.properties"), StandardCharsets.UTF_8)
                .contains("welcome"));
    }

    @Test
    @DisplayName("an umlaut survives the round trip through the override file - the trap steward/48 names")
    void umlautsSurviveTheRoundTrip() throws IOException {
        writeJar(configs.resolve("smp/smp-0.9.1.jar"), Map.of(
                "messages/smp/de.properties", "mill=Mühle\n"));
        final Path overrides = Files.createDirectories(configs.resolve("smp/smp/messages"));
        final MessageBundleLocation location = new MessageBundleLocation(
                "smp", "smp", configs.resolve("smp/smp-0.9.1.jar"), overrides, true);

        MessageBundles.write(location, "de", Map.of("mill", "Die Mühle dreht sich - äöüÄÖÜß"));

        final String onDisk = Files.readString(overrides.resolve("de.properties"), StandardCharsets.UTF_8);
        assertTrue(onDisk.contains("Mühle"),
                "written wrong, this reads \"M\\u00c3\\u00bchle\" instead - see the class javadoc: " + onDisk);
        assertFalse(onDisk.contains("\\u"), "must be a literal umlaut, never a \\uXXXX escape: " + onDisk);

        final MessageEntry entry = entry(MessageBundles.read(location), "mill");
        assertEquals("Die Mühle dreht sich - äöüÄÖÜß", entry.overrideGerman());
    }

    // ---------------------------------------------------------------------------------------
    // Placeholders
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("dropping a named placeholder is reported, never silently accepted")
    void droppingAPlaceholderIsReported() {
        assertEquals(List.of("<_sender>"),
                MessageBundles.missingPlaceholders(
                        "<_sender> waves hello", "somebody waves hello"));
        assertEquals(List.of("{price}"),
                MessageBundles.missingPlaceholders(
                        "{days} days - {price}", "{days} days - free"));
    }

    @Test
    @DisplayName("keeping the placeholder, or having none to keep, reports nothing")
    void keepingOrHavingNoPlaceholderReportsNothing() {
        assertEquals(List.of(),
                MessageBundles.missingPlaceholders("<_sender> waves hello", "<_sender> says hi"));
        assertEquals(List.of(), MessageBundles.missingPlaceholders("plain text", "different plain text"));
    }

    @Test
    @DisplayName("a plain formatting tag is not a placeholder - it carries no data to lose")
    void aPlainFormattingTagIsNotAPlaceholder() {
        assertEquals(List.of(), MessageBundles.missingPlaceholders("<bold>hi</bold>", "hi"));
    }

    // ---------------------------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------------------------

    private static MessageEntry entry(final MessageBundle bundle, final String key) {
        return bundle.entries().stream().filter(e -> e.key().equals(key)).findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no key " + key + " in " + bundle.entries().stream().map(MessageEntry::key).toList()));
    }

    /** Writes a jar with the given entry name -> UTF-8 text content, directory entries included. */
    private static void writeJar(final Path jar, final Map<String, String> entries) throws IOException {
        Files.createDirectories(jar.getParent());
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (final Map.Entry<String, String> entry : entries.entrySet()) {
                out.putNextEntry(new JarEntry(entry.getKey()));
                try (Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8) {
                    @Override
                    public void close() {
                        // Closing the wrapper must not close the JarOutputStream underneath it -
                        // there is another entry to write after this one.
                    }
                }) {
                    writer.write(entry.getValue());
                    writer.flush();
                }
                out.closeEntry();
            }
        }
    }
}
