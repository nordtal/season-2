package eu.nordtal.s2.steward.worker.configfile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.jcore.config.ConfigLoader;
import eu.nordtal.jcore.config.exception.ConfigException;
import eu.nordtal.s2.steward.worker.config.StewardSpec;
import eu.nordtal.s2.steward.worker.configfile.ConfigEntry.Kind;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Reading a jcore-written file back as a form.
 *
 * The fixtures are written by jcore itself, since this parser only has to read what jcore writes.
 */
class ConfigFilesReadTest {

    @TempDir
    Path directory;

    private Path fixture;

    @BeforeEach
    void writeFixtureWithJcore() throws ConfigException {
        fixture = directory.resolve("fixture.yml");
        ConfigLoader.builder(fixture, FixtureSpec.class).load();
    }

    @Test
    void theHeaderIsTheCommentBlockAboveTheBlankLineAtTheTop() throws IOException {
        // jcore writes no header and no comments at all: the @ConfigSpec(header=…) text never reaches the file.
        final ConfigDocument document = ConfigFiles.read(fixture);

        assertEquals(List.of(), document.header());
    }

    @Test
    void commentsAreTheBlockDirectlyAboveTheKey() throws IOException {
        // A file jcore writes carries no comments; a hand-written file with a real comment block is covered elsewhere.
        final ConfigDocument document = ConfigFiles.read(fixture);

        assertEquals(List.of(), entry(document, "port").comments());
        assertEquals(List.of(), entry(document, "stop-services").comments());
        assertEquals(List.of(), entry(document, "worker.limits.max-retries").comments());
    }

    @Test
    void theHeaderIsNotAlsoTheFirstKeysComment() throws IOException {
        final ConfigDocument document = ConfigFiles.read(fixture);

        assertFalse(entry(document, "port").comments().contains("A fixture, written by jcore."));
    }

    @Test
    void aCommentBlockWithNoBlankLineUnderItBelongsToTheKeyAndNotToTheHeader() throws IOException {
        final Path file = directory.resolve("no-header.yml");
        Files.writeString(file, """
                # This file has no header block.
                port: 8080
                """);

        final ConfigDocument document = ConfigFiles.read(file);

        assertEquals(List.of(), document.header());
        assertEquals(
                List.of("This file has no header block."),
                entry(document, "port").comments());
    }

    @Test
    void aBlankLineBetweenACommentAndAKeyEndsTheBlock() throws IOException {
        final Path file = directory.resolve("detached.yml");
        Files.writeString(file, """
                first: 1

                # this comment belongs to nobody

                second: 2
                """);

        final ConfigDocument document = ConfigFiles.read(file);

        assertEquals(List.of(), entry(document, "second").comments());
    }

    /** A nested section's key sits at its own indent column with no comment above it. */
    @Test
    void aNestedSectionsKeyCarriesNoCommentAboveItAnyMore() throws IOException {
        final List<String> lines = Files.readAllLines(fixture);
        final int keyLine =
                ConfigFiles.read(fixture).find("worker.base-url").orElseThrow().line();

        assertEquals("  base-url: http://steward-worker:8082", lines.get(keyLine - 1));
        assertFalse(
                lines.get(keyLine - 2).strip().startsWith("#"),
                "jcore 4.0.0 writes no comment above a key: " + lines.get(keyLine - 2));
        assertEquals(
                List.of(), entry(ConfigFiles.read(fixture), "worker.base-url").comments());
    }

    /** jcore 4.0.0 writes no comment at all, so a freshly-written file has no {@code #} in it. */
    @Test
    void jcoreWritesNoCommentCharacterAtAll() throws IOException {
        assertFalse(
                Files.readString(fixture).contains("#"),
                "jcore 4.0.0 must write no comment line at all: " + Files.readString(fixture));
    }

    @Test
    void theTypeOfAScalarIsWhatYamlMakesOfIt() throws IOException {
        final ConfigDocument document = ConfigFiles.read(fixture);

        assertEquals(ConfigEntry.Type.INTEGER, entry(document, "port").type());
        assertEquals(ConfigEntry.Type.DECIMAL, entry(document, "ratio").type());
        assertEquals(ConfigEntry.Type.BOOLEAN, entry(document, "enabled").type());
        assertEquals(ConfigEntry.Type.STRING, entry(document, "public-url").type());
        // '12', quoted by jcore because the spec method returns a String, and read back as one.
        assertEquals(ConfigEntry.Type.STRING, entry(document, "build-number").type());
        assertEquals("12", entry(document, "build-number").value());
        assertEquals(ConfigEntry.Type.STRING, entry(document, "api-token").type());
        assertEquals("", entry(document, "api-token").value());
    }

    @Test
    void sectionsAreMapsAndSequencesAreLists() throws IOException {
        final ConfigDocument document = ConfigFiles.read(fixture);

        assertEquals(Kind.MAP, entry(document, "worker").kind());
        assertEquals(Kind.MAP, entry(document, "worker.limits").kind());
        assertEquals(Kind.LIST, entry(document, "stop-services").kind());
        assertEquals(Kind.LIST, entry(document, "empty-list").kind());
        assertEquals(Kind.SCALAR, entry(document, "worker.limits.max-retries").kind());

        // A section has no value of its own; a list of scalars does, and it is rewritten whole.
        assertFalse(entry(document, "worker").editable());
        assertTrue(entry(document, "stop-services").editable());
        assertTrue(entry(document, "empty-list").editable());
        assertTrue(entry(document, "worker.limits.max-retries").editable());

        assertEquals(
                List.of("discord-bot", "smp"), entry(document, "stop-services").items());
        assertEquals(List.of(), entry(document, "empty-list").items());
        assertEquals(List.of(), entry(document, "worker").items());
    }

    @Test
    void everyKeyIsThereInFileOrderWithItsSectionInFrontOfIt() throws IOException {
        final ConfigDocument document = ConfigFiles.read(fixture);

        assertEquals(
                List.of(
                        "port",
                        "ratio",
                        "enabled",
                        "public-url",
                        "build-number",
                        "api-token",
                        "stop-services",
                        "empty-list",
                        "worker",
                        "worker.base-url",
                        "worker.token",
                        "worker.limits",
                        "worker.limits.max-retries"),
                document.entries().stream().map(ConfigEntry::path).toList());
    }

    @Test
    void theLineIsTheOneTheKeyIsOn() throws IOException {
        final ConfigDocument document = ConfigFiles.read(fixture);
        final List<String> lines = Files.readAllLines(fixture);

        for (final ConfigEntry e : document.entries()) {
            assertTrue(
                    lines.get(e.line() - 1).strip().startsWith(e.key() + ":"),
                    e.path() + " says line " + e.line() + ", which is: " + lines.get(e.line() - 1));
        }
    }

    @Test
    void theLabelIsTheKeyWithItsHyphensTakenOut() throws IOException {
        final ConfigDocument document = ConfigFiles.read(fixture);

        assertEquals("Port", entry(document, "port").label());
        assertEquals("Public URL", entry(document, "public-url").label());
        assertEquals("Stop services", entry(document, "stop-services").label());
        assertEquals("Base URL", entry(document, "worker.base-url").label());
        assertEquals("Max retries", entry(document, "worker.limits.max-retries").label());
        assertEquals("Stop services", Labels.of("stop_services"));
        assertEquals("Session days", Labels.of("session-days"));
    }

    @Test
    void aKeyThatNamesACredentialIsMarkedSecretAndStillCarriesItsValue() throws IOException {
        final Path file = directory.resolve("secrets.yml");
        Files.writeString(file, """
                api-token: abcd
                client-secret: efgh
                password: ijkl
                signing-key: mnop
                public-url: https://example.invalid
                port: 8080
                """);

        final ConfigDocument document = ConfigFiles.read(file);

        assertTrue(entry(document, "api-token").secret());
        assertTrue(entry(document, "client-secret").secret());
        assertTrue(entry(document, "password").secret());
        assertTrue(entry(document, "signing-key").secret());
        assertFalse(entry(document, "public-url").secret());
        assertFalse(entry(document, "port").secret());
        // The value is still there: a form that could not read it could not save it back either.
        assertEquals("abcd", entry(document, "api-token").value());
    }

    @Test
    void thisServicesOwnConfigReadsBackTheWayItsSpecDescribesIt() throws IOException, ConfigException {
        // A real config written by jcore, not a fixture written to be easy to parse and self-agreeing.
        final Path file = directory.resolve("steward.yml");
        ConfigLoader.builder(file, StewardSpec.class).load();

        final ConfigDocument document = ConfigFiles.read(file);

        // jcore writes no header; StewardSpec's own header text is not in the file.
        assertEquals(List.of(), document.header());
        assertEquals(ConfigEntry.Type.INTEGER, entry(document, "api.port").type());
        assertEquals("8082", entry(document, "api.port").value());
        assertEquals(Kind.MAP, entry(document, "api").kind());
        assertEquals("/configs", entry(document, "api.configs-root").value());
        assertTrue(entry(document, "api.token").secret());
        assertTrue(entry(document, "github-token").secret());
        assertEquals(Kind.LIST, entry(document, "backup.volumes").kind());
    }

    /** The same shapes again, in a hand-made fixture that does not drift with the real spec. */
    @Test
    void aWorkerStyleFileWithAListAndTwoSectionsReadsBack() throws IOException {
        final Path file = directory.resolve("steward.yml");
        Files.writeString(file, """
                # -------------------------------------------------------------------
                #   steward-worker - where the versions come from
                # -------------------------------------------------------------------
                # The defaults are the real values for nordtal.eu.

                # The GitHub repository the jars come from, as owner/name.
                season-repo: nordtal/season-2

                # What a backup stops before it copies, and starts again afterwards.
                backup:

                  # The compose services, by name.
                  stop-services:
                  - discord-bot
                  - smp

                  # How many to keep.
                  keep: 7
                """);

        final ConfigDocument document = ConfigFiles.read(file);

        assertEquals(4, document.header().size());
        assertEquals(
                "  steward-worker - where the versions come from",
                document.header().get(1));
        assertEquals("nordtal/season-2", entry(document, "season-repo").value());
        assertEquals(Kind.MAP, entry(document, "backup").kind());
        assertEquals(Kind.LIST, entry(document, "backup.stop-services").kind());
        assertEquals(
                List.of("The compose services, by name."),
                entry(document, "backup.stop-services").comments());
        assertEquals(ConfigEntry.Type.INTEGER, entry(document, "backup.keep").type());
        assertEquals(
                List.of("season-repo", "backup", "backup.stop-services", "backup.keep"),
                document.entries().stream().map(ConfigEntry::path).toList());
    }

    @Test
    void aBlockScalarIsReadAsTheTextItHolds() throws IOException {
        final Path file = directory.resolve("block.yml");
        Files.writeString(file, """
                motd: |-
                  line one
                  line two
                port: 25565
                """);

        final ConfigDocument document = ConfigFiles.read(file);

        assertEquals("line one\nline two", entry(document, "motd").value());
        assertEquals(Kind.SCALAR, entry(document, "motd").kind());
        assertTrue(entry(document, "motd").editable(), "a block scalar is rewritten as a block, so it is editable");
        assertTrue(entry(document, "port").editable());
    }

    @Test
    void anEmptyFileIsADocumentWithNoEntries() throws IOException {
        final Path file = directory.resolve("empty.yml");
        Files.writeString(file, "");

        final ConfigDocument document = ConfigFiles.read(file);

        assertEquals(List.of(), document.entries());
        assertEquals(List.of(), document.header());
    }

    @Test
    void aFileOfNothingButCommentsIsAllHeader() throws IOException {
        final Path file = directory.resolve("comments-only.yml");
        Files.writeString(file, "# nothing here yet\n");

        assertEquals(List.of("nothing here yet"), ConfigFiles.read(file).header());
    }

    @Test
    void brokenYamlNamesTheLine() throws IOException {
        final Path file = directory.resolve("broken.yml");
        Files.writeString(file, """
                port: 8080
                worker:
                   base-url: http://x
                  token: ''
                """);

        final IOException thrown = assertThrows(IOException.class, () -> ConfigFiles.read(file));

        assertTrue(thrown.getMessage().contains("line 4"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("broken.yml"), thrown.getMessage());
    }

    @Test
    void aFileThatIsNotASetOfKeysIsNotAConfigFile() throws IOException {
        final Path file = directory.resolve("list.yml");
        Files.writeString(file, "- one\n- two\n");

        final IOException thrown = assertThrows(IOException.class, () -> ConfigFiles.read(file));

        assertTrue(thrown.getMessage().contains("line 1"), thrown.getMessage());
    }

    private static ConfigEntry entry(final ConfigDocument document, final String path) {
        return document.find(path)
                .orElseThrow(() -> new AssertionError("no entry " + path + " in "
                        + document.entries().stream().map(ConfigEntry::path).toList()));
    }
}
