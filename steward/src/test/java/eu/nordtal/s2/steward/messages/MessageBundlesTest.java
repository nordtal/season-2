package eu.nordtal.s2.steward.messages;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The bundles a module ships in its jar, merged with an operator's override.
 *
 * Fixture jars are written as real zips, since opening an arbitrary path as a zip is what is under test.
 */
class MessageBundlesTest {

    @TempDir
    Path configs;

    @TempDir
    Path volumes;

    // Discovery

    @Test
    void aPaperPluginsBundleIsFoundNextToItsOwnJarInTheConfigsMount() throws IOException {
        writeJar(configs.resolve("smp/smp-0.9.1.jar"), Map.of("messages/smp/en.properties", "welcome=Welcome\n"));
        Files.createDirectories(configs.resolve("smp/smp/messages"));

        final List<MessageBundleLocation> found = MessageBundles.discover(configs, volumes);

        assertEquals(1, found.size(), found.toString());
        assertEquals("smp", found.getFirst().service());
        assertEquals("smp", found.getFirst().module());
        assertEquals(configs.resolve("smp/smp-0.9.1.jar"), found.getFirst().jar());
    }

    @Test
    void aStandaloneJarsBundleHasNoModuleAndItsJarIsFoundInTheVolumesMount() throws IOException {
        // discord-bot's own jar is not under the configs mount at all, only its data is.
        Files.createDirectories(configs.resolve("discord-bot/messages"));
        writeJar(
                volumes.resolve("discord-bot/discord-bot-0.9.1.jar"),
                Map.of("messages/access/en.properties", "contribution.title=Access\n"));

        final List<MessageBundleLocation> found = MessageBundles.discover(configs, volumes);

        assertEquals(1, found.size(), found.toString());
        assertEquals("discord-bot", found.getFirst().service());
        assertEquals("", found.getFirst().module());
        assertEquals(
                volumes.resolve("discord-bot/discord-bot-0.9.1.jar"),
                found.getFirst().jar());
    }

    @Test
    void aMessagesDirectoryWithNoJarToMatchItIsSkippedNotReportedBroken() throws IOException {
        Files.createDirectories(configs.resolve("limbo/limbo/messages"));
        // No jar anywhere: a deployment installing this module for the first time.

        assertEquals(List.of(), MessageBundles.discover(configs, volumes));
    }

    @Test
    void aRootThatIsNotMountedIsAnEmptyListNotAFailure() {
        assertEquals(List.of(), MessageBundles.discover(configs.resolve("never-mounted"), volumes));
    }

    // Reading and merging

    @Test
    void severalRootsInOneJarAreMergedIntoOneBundleTheWayMessagesLoadMergesThem() throws IOException {
        writeJar(
                configs.resolve("smp/smp-0.9.1.jar"),
                Map.of(
                        "messages/paper-common/en.properties", "reload.done=Reloaded\n",
                        "messages/paper-common/de.properties", "reload.done=Neu geladen\n",
                        "messages/smp/en.properties", "welcome=Welcome\n",
                        "messages/smp/de.properties", "welcome=Willkommen\n"));
        final Path overrides = Files.createDirectories(configs.resolve("smp/smp/messages"));
        final MessageBundleLocation location =
                new MessageBundleLocation("smp", "smp", configs.resolve("smp/smp-0.9.1.jar"), overrides, true);

        final MessageBundle bundle = MessageBundles.read(location);

        assertEquals(
                List.of("reload.done", "welcome"),
                bundle.entries().stream().map(MessageEntry::key).sorted().toList());
        final MessageEntry welcome = entry(bundle, "welcome");
        assertEquals("Welcome", welcome.english());
        assertEquals("Willkommen", welcome.german());
        assertTrue(welcome.inBundle());
    }

    private static final String SCHEMA = """
            {"bundle": "smp", "messages": [
              {"key": "welcome", "name": "Welcome", "args": [], "section": ["Join"]},
              {"key": "duel.won", "name": "Duel won", "description": "Sent to the winner.",
               "args": [{"name": "opponent", "component": false}, {"name": "_link", "component": true}],
               "section": ["Duels", null]}
            ]}
            """;

    @Test
    void theJarsSchemaNamesEachKeyInTheOrderOfTheEnglishFileTheRestAfterIt() throws IOException {
        writeJar(
                configs.resolve("smp/smp-0.9.1.jar"),
                Map.of(
                        "messages/smp/en.properties",
                        "welcome=Welcome\nduel.won=You beat {opponent} <_link>\n",
                        "messages/smp/schema.json",
                        SCHEMA));
        final Path overrides = Files.createDirectories(configs.resolve("smp/smp/messages"));
        Files.writeString(overrides.resolve("en.properties"), "a.typo=Oops\n", StandardCharsets.UTF_8);
        final MessageBundleLocation location =
                new MessageBundleLocation("smp", "smp", configs.resolve("smp/smp-0.9.1.jar"), overrides, true);

        final MessageBundle bundle = MessageBundles.read(location);

        assertEquals(
                List.of("welcome", "duel.won", "a.typo"),
                bundle.entries().stream().map(MessageEntry::key).toList());
        final MessageEntry won = entry(bundle, "duel.won");
        assertEquals("Duel won", won.name());
        assertEquals("Sent to the winner.", won.description());
        assertEquals(List.of(new MessageArg("opponent", false), new MessageArg("_link", true)), won.args());
        assertEquals(Arrays.asList("Duels", null), won.section());
        assertNull(entry(bundle, "a.typo").name());
    }

    @Test
    void aPlaceholderTheSchemaDoesNotDeclareIsFoundADeclaredOneAndFormattingAreNot() throws IOException {
        writeJar(
                configs.resolve("smp/smp-0.9.1.jar"),
                Map.of(
                        "messages/smp/en.properties",
                        "welcome=Welcome\nduel.won=You beat {opponent} <_link>\n",
                        "messages/smp/schema.json",
                        SCHEMA));
        final Path overrides = Files.createDirectories(configs.resolve("smp/smp/messages"));
        final MessageBundle bundle = MessageBundles.read(
                new MessageBundleLocation("smp", "smp", configs.resolve("smp/smp-0.9.1.jar"), overrides, true));
        final MessageEntry won = entry(bundle, "duel.won");

        assertEquals(List.of(), MessageBundles.unknownPlaceholders(won, "<bold>{opponent}</bold> lost <_link>"));
        assertEquals(
                List.of("{oponent}", "<_player>"),
                MessageBundles.unknownPlaceholders(won, "You beat {oponent} <_player> {oponent}"));
        assertEquals(
                List.of("{player}"), MessageBundles.unknownPlaceholders(entry(bundle, "welcome"), "Welcome {player}"));
    }

    private static final String ROLE_SCHEMA = """
            {"bundle": "smp", "messages": [
              {"key": "duel.won", "name": "Duel won", "format": "MINIMESSAGE", "shown": "TITLE",
               "args": [{"name": "winner", "component": false, "context": "player"},
                        {"name": "count", "component": false}],
               "section": []}
            ],
             "contexts": {"player": {"name": "Player", "properties": ["name"]},
                          "service": {"name": "Service", "properties": ["name"]},
                          "season": {"name": "Season", "properties": ["number"]}},
             "globals": [{"name": "server", "context": "service"}, {"name": "season", "context": "season"}]}
            """;

    @Test
    void aRoleBecomesOnePlaceholderPerPropertyOfItsTypeAndEveryMessageGetsTheGlobals() throws IOException {
        writeJar(
                configs.resolve("smp/smp-0.9.1.jar"),
                Map.of(
                        "messages/smp/en.properties",
                        "duel.won={winner.name} won {count}\n",
                        "messages/smp/schema.json",
                        ROLE_SCHEMA));
        final Path overrides = Files.createDirectories(configs.resolve("smp/smp/messages"));
        final MessageEntry won = entry(
                MessageBundles.read(
                        new MessageBundleLocation("smp", "smp", configs.resolve("smp/smp-0.9.1.jar"), overrides, true)),
                "duel.won");

        assertEquals(
                List.of(
                        new MessageArg("winner.name", false, "player", false),
                        new MessageArg("count", false),
                        new MessageArg("server.name", false, "service", true),
                        new MessageArg("season.number", false, "season", true)),
                won.args());
        assertEquals("MINIMESSAGE", won.format());
        assertEquals("TITLE", won.shown());
        assertEquals(
                List.of("{winner.nope}", "{winner}"),
                MessageBundles.unknownPlaceholders(
                        won, "{winner.name} {server.name} {season.number} {winner.nope} {winner}"));
    }

    @Test
    void aKeyWithoutASchemaIsShownAndNeverChecked() throws IOException {
        writeJar(configs.resolve("smp/smp-0.9.1.jar"), Map.of("messages/smp/en.properties", "welcome=Welcome\n"));
        final Path overrides = Files.createDirectories(configs.resolve("smp/smp/messages"));
        final MessageEntry welcome = entry(
                MessageBundles.read(
                        new MessageBundleLocation("smp", "smp", configs.resolve("smp/smp-0.9.1.jar"), overrides, true)),
                "welcome");

        assertNull(welcome.name());
        assertEquals(List.of(), MessageBundles.unknownPlaceholders(welcome, "Welcome {player}"));
    }

    @Test
    void aKeyMissingFromTheGermanBundleIsARealGapNotSilentlyFilledWithEnglish() throws IOException {
        writeJar(
                configs.resolve("smp/smp-0.9.1.jar"),
                Map.of(
                        "messages/smp/en.properties", "new-feature=New!\n",
                        "messages/smp/de.properties", "# nothing translated yet\n"));
        final Path overrides = Files.createDirectories(configs.resolve("smp/smp/messages"));
        final MessageBundleLocation location =
                new MessageBundleLocation("smp", "smp", configs.resolve("smp/smp-0.9.1.jar"), overrides, true);

        final MessageEntry entry = entry(MessageBundles.read(location), "new-feature");

        assertEquals("New!", entry.english());
        assertNull(entry.german());
    }

    @Test
    void anOverrideIsCarriedBesideThePackagedTextNotMergedOverIt() throws IOException {
        writeJar(configs.resolve("smp/smp-0.9.1.jar"), Map.of("messages/smp/en.properties", "welcome=Welcome\n"));
        final Path overrides = Files.createDirectories(configs.resolve("smp/smp/messages"));
        Files.writeString(overrides.resolve("en.properties"), "welcome=Howdy\n", StandardCharsets.UTF_8);
        final MessageBundleLocation location =
                new MessageBundleLocation("smp", "smp", configs.resolve("smp/smp-0.9.1.jar"), overrides, true);

        final MessageEntry entry = entry(MessageBundles.read(location), "welcome");

        assertEquals("Welcome", entry.english(), "the packaged text must still be visible");
        assertEquals("Howdy", entry.overrideEnglish());
    }

    @Test
    void aKeyOnlyAnOverrideNamesThatNoBundleDeclaresIsShownAndMarkedAsSuch() throws IOException {
        writeJar(configs.resolve("smp/smp-0.9.1.jar"), Map.of("messages/smp/en.properties", "welcome=Welcome\n"));
        final Path overrides = Files.createDirectories(configs.resolve("smp/smp/messages"));
        Files.writeString(overrides.resolve("en.properties"), "typo-key=oops\n", StandardCharsets.UTF_8);
        final MessageBundleLocation location =
                new MessageBundleLocation("smp", "smp", configs.resolve("smp/smp-0.9.1.jar"), overrides, true);

        final MessageEntry entry = entry(MessageBundles.read(location), "typo-key");

        assertEquals("oops", entry.overrideEnglish());
        assertFalse(entry.inBundle());
    }

    // Writing, and the encoding round trip

    @Test
    void savingALineCreatesTheOverrideAndResettingItRemovesTheKeyEntirely() throws IOException {
        writeJar(configs.resolve("smp/smp-0.9.1.jar"), Map.of("messages/smp/en.properties", "welcome=Welcome\n"));
        final Path overrides = Files.createDirectories(configs.resolve("smp/smp/messages"));
        final MessageBundleLocation location =
                new MessageBundleLocation("smp", "smp", configs.resolve("smp/smp-0.9.1.jar"), overrides, true);

        MessageBundles.write(location, "en", Map.of("welcome", "Howdy"));
        assertEquals("Howdy", entry(MessageBundles.read(location), "welcome").overrideEnglish());

        // Resetting deletes the key from the override rather than copying the English text into it and freezing it.
        final Map<String, String> reset = new java.util.HashMap<>();
        reset.put("welcome", null);
        MessageBundles.write(location, "en", reset);
        assertNull(entry(MessageBundles.read(location), "welcome").overrideEnglish());
        assertFalse(Files.readString(overrides.resolve("en.properties"), StandardCharsets.UTF_8)
                .contains("welcome"));
    }

    @Test
    void anUmlautSurvivesTheRoundTripThroughTheOverrideFile() throws IOException {
        writeJar(configs.resolve("smp/smp-0.9.1.jar"), Map.of("messages/smp/de.properties", "mill=Mühle\n"));
        final Path overrides = Files.createDirectories(configs.resolve("smp/smp/messages"));
        final MessageBundleLocation location =
                new MessageBundleLocation("smp", "smp", configs.resolve("smp/smp-0.9.1.jar"), overrides, true);

        MessageBundles.write(location, "de", Map.of("mill", "Die Mühle dreht sich - äöüÄÖÜß"));

        final String onDisk = Files.readString(overrides.resolve("de.properties"), StandardCharsets.UTF_8);
        assertTrue(
                onDisk.contains("Mühle"),
                "written wrong, this reads \"M\\u00c3\\u00bchle\" instead - see the class javadoc: " + onDisk);
        assertFalse(onDisk.contains("\\u"), "must be a literal umlaut, never a \\uXXXX escape: " + onDisk);

        final MessageEntry entry = entry(MessageBundles.read(location), "mill");
        assertEquals("Die Mühle dreht sich - äöüÄÖÜß", entry.overrideGerman());
    }

    // Placeholders

    @Test
    void droppingANamedPlaceholderIsReportedNeverSilentlyAccepted() {
        assertEquals(
                List.of("<_sender>"),
                MessageBundles.missingPlaceholders("<_sender> waves hello", "somebody waves hello"));
        assertEquals(
                List.of("{price}"), MessageBundles.missingPlaceholders("{days} days - {price}", "{days} days - free"));
    }

    @Test
    void keepingThePlaceholderOrHavingNoneToKeepReportsNothing() {
        assertEquals(List.of(), MessageBundles.missingPlaceholders("<_sender> waves hello", "<_sender> says hi"));
        assertEquals(List.of(), MessageBundles.missingPlaceholders("plain text", "different plain text"));
    }

    @Test
    void aPlainFormattingTagIsNotAPlaceholderItCarriesNoDataToLose() {
        assertEquals(List.of(), MessageBundles.missingPlaceholders("<bold>hi</bold>", "hi"));
    }

    // Fixtures

    private static MessageEntry entry(final MessageBundle bundle, final String key) {
        return bundle.entries().stream()
                .filter(e -> e.key().equals(key))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no key " + key + " in "
                        + bundle.entries().stream().map(MessageEntry::key).toList()));
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
                        // Closing the wrapper must not close the JarOutputStream underneath it: another entry follows.
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
