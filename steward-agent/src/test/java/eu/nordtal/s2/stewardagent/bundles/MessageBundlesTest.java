package eu.nordtal.s2.stewardagent.bundles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.internalapi.agent.MessageArg;
import eu.nordtal.s2.internalapi.agent.MessageBundle;
import eu.nordtal.s2.internalapi.agent.MessageEntry;
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
 * The bundles a module ships in its jar, read for the editor.
 *
 * Fixture jars are written as real zips, since opening an arbitrary path as a zip is what is under test.
 */
class MessageBundlesTest {

    @TempDir
    Path configs;

    @TempDir
    Path images;

    /** The bot's jar as the agent copies it out of the running container, keyed by service. */
    private ImageJars imageJars() {
        return service -> {
            final Path jar = images.resolve(service + ".jar");
            return Files.isRegularFile(jar) ? jar : null;
        };
    }

    // Discovery

    @Test
    void aPaperPluginsBundleIsFoundNextToItsOwnJarInTheConfigsMount() throws IOException {
        writeJar(configs.resolve("smp/smp-0.9.1.jar"), Map.of("messages/smp/en.properties", "welcome=Welcome\n"));
        writeJar(configs.resolve("smp/chunky-1.4.jar"), Map.of("plugin.yml", "name: Chunky\n"));

        final List<MessageBundleLocation> found = MessageBundles.discover(configs, imageJars());

        assertEquals(1, found.size(), found.toString());
        assertEquals("smp", found.getFirst().service());
        assertEquals("smp", found.getFirst().module());
        assertEquals(configs.resolve("smp/smp-0.9.1.jar"), found.getFirst().jar());
    }

    @Test
    void aWholeServicesBundleHasNoModuleAndItsJarComesFromItsImage() throws IOException {
        // discord-bot's own jar is in its image, not on any volume; only its data is under the configs mount.
        Files.createDirectories(configs.resolve("discord-bot"));
        writeJar(
                images.resolve("discord-bot.jar"),
                Map.of("messages/access/en.properties", "contribution.title=Access\n"));

        final List<MessageBundleLocation> found = MessageBundles.discover(configs, imageJars());

        assertEquals(1, found.size(), found.toString());
        assertEquals("discord-bot", found.getFirst().service());
        assertEquals("", found.getFirst().module());
        assertEquals(images.resolve("discord-bot.jar"), found.getFirst().jar());
    }

    @Test
    void aServiceWithNoJarAnywhereIsSkippedNotReportedBroken() throws IOException {
        Files.createDirectories(configs.resolve("limbo"));
        // No jar anywhere: a deployment installing this module for the first time.

        assertEquals(List.of(), MessageBundles.discover(configs, imageJars()));
    }

    @Test
    void aRootThatIsNotMountedIsAnEmptyListNotAFailure() {
        assertEquals(List.of(), MessageBundles.discover(configs.resolve("never-mounted"), imageJars()));
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
        final MessageBundleLocation location =
                new MessageBundleLocation("smp", "smp", configs.resolve("smp/smp-0.9.1.jar"));

        final MessageBundle bundle = MessageBundles.read(location);

        assertEquals(
                List.of("reload.done", "welcome"),
                bundle.entries().stream().map(MessageEntry::key).sorted().toList());
        final MessageEntry welcome = entry(bundle, "welcome");
        assertEquals(List.of("Welcome"), welcome.packaged("en"));
        assertEquals(List.of("Willkommen"), welcome.packaged("de"));
        assertTrue(welcome.inBundle());
    }

    private static final String SCHEMA = """
            {"bundle": "smp", "messages": [
              {"key": "welcome", "name": "Welcome", "args": [], "section": ["Join"],
               "format": "MINIMESSAGE", "shown": "CHAT"},
              {"key": "duel.won", "name": "Duel won", "description": "Sent to the winner.",
               "args": [{"name": "opponent", "kind": "name", "example": "Alex", "action": false},
                        {"name": "rematch", "action": true}],
               "section": ["Duels", null], "format": "MINIMESSAGE", "shown": "CHAT"}
            ], "contexts": {}, "globals": []}
            """;

    @Test
    void theJarsSchemaNamesEachKeyInTheOrderOfTheEnglishFileTheRestAfterIt() throws IOException {
        writeJar(
                configs.resolve("smp/smp-0.9.1.jar"),
                Map.of(
                        "messages/smp/en.properties",
                        "welcome=Welcome\nduel.won=You beat {opponent} <action:rematch>[Again]</action>\na.typo=Oops\n",
                        "messages/smp/schema.json",
                        SCHEMA));
        final MessageBundleLocation location =
                new MessageBundleLocation("smp", "smp", configs.resolve("smp/smp-0.9.1.jar"));

        final MessageBundle bundle = MessageBundles.read(location);

        assertEquals(
                List.of("welcome", "duel.won", "a.typo"),
                bundle.entries().stream().map(MessageEntry::key).toList());
        final MessageEntry won = entry(bundle, "duel.won");
        assertEquals("Duel won", won.name());
        assertEquals("Sent to the winner.", won.description());
        assertEquals(
                List.of(
                        new MessageArg("opponent", "name", null, false, "Alex", false),
                        new MessageArg("rematch", null, null, false, null, true)),
                won.args());
        assertEquals(Arrays.asList("Duels", null), won.section());
        assertNull(entry(bundle, "a.typo").name());
    }

    private static final String ROLE_SCHEMA = """
            {"bundle": "smp", "messages": [
              {"key": "duel.won", "name": "Duel won", "format": "MINIMESSAGE", "shown": "TITLE",
               "args": [{"name": "winner", "context": "player", "action": false},
                        {"name": "count", "kind": "number", "example": "3", "action": false}],
               "section": []}
            ],
             "contexts": {"player": {"name": "Player", "attributes": [
                              {"name": "name", "kind": "name", "example": "Alex"},
                              {"name": "self", "kind": "choice", "example": "false"}]},
                          "service": {"name": "Service", "attributes": [
                              {"name": "name", "kind": "text", "example": "smp"}]},
                          "season": {"name": "Season", "attributes": [
                              {"name": "number", "kind": "number", "example": "2"}]}},
             "globals": [{"name": "server", "context": "service"}, {"name": "season", "context": "season"}]}
            """;

    @Test
    void aRoleBecomesOnePlaceholderPerAttributeOfItsTypeAndEveryMessageGetsTheGlobals() throws IOException {
        writeJar(
                configs.resolve("smp/smp-0.9.1.jar"),
                Map.of(
                        "messages/smp/en.properties",
                        "duel.won={winner.name} won {count}\n",
                        "messages/smp/schema.json",
                        ROLE_SCHEMA));
        final MessageEntry won = entry(
                MessageBundles.read(new MessageBundleLocation("smp", "smp", configs.resolve("smp/smp-0.9.1.jar"))),
                "duel.won");

        assertEquals(
                List.of(
                        new MessageArg("winner.name", "name", "player", false, "Alex", false),
                        new MessageArg("winner.self", "choice", "player", false, "false", false),
                        new MessageArg("count", "number", null, false, "3", false),
                        new MessageArg("server.name", "text", "service", true, "smp", false),
                        new MessageArg("season.number", "number", "season", true, "2", false)),
                won.args());
        assertEquals("MINIMESSAGE", won.format());
        assertEquals("TITLE", won.shown());
    }

    private static final String NESTING_SCHEMA = """
            {"bundle": "proxy", "messages": [
              {"key": "restart.countdown.update", "name": "Update countdown", "format": "MINIMESSAGE",
               "shown": "CHAT", "section": [],
               "args": [{"name": "what", "kind": "message", "example": "restart.what.network", "action": false},
                        {"name": "count", "kind": "number", "example": "10", "action": false}]}
            ], "contexts": {}, "globals": []}
            """;

    @Test
    void aValueThatIsAMessageCarriesItsExampleKeysWordsFromAnyBundleOfTheJar() throws IOException {
        writeJar(
                configs.resolve("proxy/proxy-0.9.1.jar"),
                Map.of(
                        "messages/proxy/en.properties",
                        "restart.countdown.update={what} is being updated in {count} seconds\n",
                        "messages/proxy/schema.json",
                        NESTING_SCHEMA,
                        "messages/restart/en.properties",
                        "restart.what.network=The network\n",
                        "messages/restart/de.properties",
                        "restart.what.network=Das Netzwerk\n"));
        final MessageEntry update = entry(
                MessageBundles.read(
                        new MessageBundleLocation("proxy", "proxy", configs.resolve("proxy/proxy-0.9.1.jar"))),
                "restart.countdown.update");

        assertEquals(
                List.of(
                        new MessageArg(
                                "what",
                                "message",
                                null,
                                false,
                                "restart.what.network",
                                false,
                                Map.of("en", "The network", "de", "Das Netzwerk")),
                        new MessageArg("count", "number", null, false, "10", false)),
                update.args(),
                "the editor previews the nested message as its words and still sends the server its key");
    }

    @Test
    void aKeyMissingFromTheGermanBundleIsARealGapNotSilentlyFilledWithEnglish() throws IOException {
        writeJar(
                configs.resolve("smp/smp-0.9.1.jar"),
                Map.of(
                        "messages/smp/en.properties", "new-feature=New!\n",
                        "messages/smp/de.properties", "# nothing translated yet\n"));
        final MessageBundleLocation location =
                new MessageBundleLocation("smp", "smp", configs.resolve("smp/smp-0.9.1.jar"));

        final MessageEntry entry = entry(MessageBundles.read(location), "new-feature");

        assertEquals(List.of("New!"), entry.packaged("en"));
        assertEquals(java.util.Set.of("en"), entry.texts().keySet(), "an untranslated key has no German at all");
    }

    @Test
    void aKeyCarriesItsBundleItsFirstTextAndTheHashOfEveryVariantAnOverrideRecords() throws IOException {
        writeJar(
                configs.resolve("smp/smp-0.9.1.jar"),
                Map.of(
                        "messages/smp/en.properties", "cheer=Hooray!\ncheer[1]=Yay!\n",
                        "messages/smp/de.properties", "mill=Mühle\n",
                        "messages/paper-common/en.properties", "reload.done=Reloaded\n"));

        final MessageBundle bundle =
                MessageBundles.read(new MessageBundleLocation("smp", "smp", configs.resolve("smp/smp-0.9.1.jar")));

        final MessageEntry cheer = entry(bundle, "cheer");
        assertEquals("smp", cheer.bundle());
        assertEquals(List.of("Hooray!", "Yay!"), cheer.packaged("en"));
        assertEquals(List.of(), cheer.packaged("de"));
        assertEquals(List.of("Mühle"), entry(bundle, "mill").packaged("de"));
        assertEquals("paper-common", entry(bundle, "reload.done").bundle());
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
