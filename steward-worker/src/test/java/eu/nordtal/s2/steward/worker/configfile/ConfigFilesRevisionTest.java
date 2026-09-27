package eu.nordtal.s2.steward.worker.configfile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.jcore.config.ConfigLoader;
import eu.nordtal.jcore.config.exception.ConfigException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The revision, which stops the second of two open forms from silently undoing the first.
 *
 * Without it the second save re-applies its own change to the new file and quietly reverts the first admin's edit.
 */
class ConfigFilesRevisionTest {

    @TempDir
    Path directory;

    private Path fixture;

    @BeforeEach
    void writeFixtureWithJcore() throws ConfigException {
        fixture = directory.resolve("fixture.yml");
        ConfigLoader.builder(fixture, FixtureSpec.class).load();
    }

    // What a revision is a revision of

    @Test
    void aFileThatIsWrittenSaysSomethingDifferentAfterwards() throws IOException {
        final String before = ConfigFiles.read(fixture).revision();

        final ConfigDocument after = ConfigFiles.write(fixture, Map.of("port", ConfigChange.of("9090")), before);

        assertNotEquals(before, after.revision());
        assertEquals(
                ConfigFiles.read(fixture).revision(),
                after.revision(),
                "the revision handed back by the write is not the one a fresh read of the same"
                        + " file produces - so the browser's next save is refused for no reason,"
                        + " and the only way out of it is a reload");
    }

    @Test
    void theSameBytesAlwaysGiveTheSameRevisionWhereverTheyAreAndWhenever() throws IOException {
        // Two different files, directories and moments: the revision must depend on content alone, not path or clock.
        final Path elsewhere =
                Files.createDirectory(directory.resolve("second")).resolve("fixture.yml");
        Files.writeString(elsewhere, Files.readString(fixture));

        assertEquals(
                ConfigFiles.read(fixture).revision(),
                ConfigFiles.read(elsewhere).revision());
        assertEquals(
                ConfigFiles.read(fixture).revision(), ConfigFiles.read(fixture).revision());
    }

    @Test
    void aSavedValueThatChangesNothingLeavesTheRevisionWhereItWas() throws IOException {
        // Resaving the same characters must not move the revision, or every open form would invalidate every other.
        final String before = ConfigFiles.read(fixture).revision();
        final byte[] bytes = Files.readAllBytes(fixture);

        final ConfigDocument after = ConfigFiles.write(fixture, Map.of("port", ConfigChange.of("8080")), before);

        assertArrayEquals(bytes, Files.readAllBytes(fixture));
        assertEquals(before, after.revision());
    }

    @Test
    void twoContentsOfTheSameLengthAreTwoRevisions() throws IOException {
        // The javadoc rules out size and mtime by name; a one-character edit is what neither can see at all.
        final String before = ConfigFiles.read(fixture).revision();
        ConfigFiles.write(fixture, Map.of("port", ConfigChange.of("8081")), before);

        assertEquals(
                Files.readString(fixture).length(),
                Files.readString(fixture).replace("8081", "8080").length(),
                "the fixture no longer makes this test's point - pick another equal-length edit");
        assertNotEquals(before, ConfigFiles.read(fixture).revision());
    }

    @Test
    void anEditTheParserDoesNotCareAboutStillMovesTheRevision() throws IOException {
        // An edit made outside this process still changes the characters, so parsing the document alone misses it.
        final String before = ConfigFiles.read(fixture).revision();
        Files.writeString(fixture, Files.readString(fixture) + "\n# added by hand\n");

        assertNotEquals(before, ConfigFiles.read(fixture).revision());
    }

    // What a stale revision costs

    @Test
    void aSaveAgainstARevisionThatHasMovedOnIsRefusedAndWritesNothingAtAll() throws IOException {
        final String whatTheSecondFormStillShows = ConfigFiles.read(fixture).revision();

        // The first admin saves.
        ConfigFiles.write(fixture, Map.of("port", ConfigChange.of("9090")), whatTheSecondFormStillShows);
        final byte[] afterTheFirstSave = Files.readAllBytes(fixture);

        // The second admin saves a different key, from a form drawn before that.
        final StaleConfigException refused = assertThrows(
                StaleConfigException.class,
                () -> ConfigFiles.write(
                        fixture, Map.of("enabled", ConfigChange.of("false")), whatTheSecondFormStillShows));

        assertArrayEquals(
                afterTheFirstSave,
                Files.readAllBytes(fixture),
                "the refused save still changed the file - which is the exact outcome the"
                        + " revision exists to prevent, now with an error message on top of it");
        assertEquals(whatTheSecondFormStillShows, refused.expected());
        assertEquals(
                ConfigFiles.read(fixture).revision(),
                refused.actual(),
                "the exception's `actual` is not what the file says, so the message it produces"
                        + " sends whoever reads it looking for a revision that never existed");
        assertTrue(refused.getMessage().contains(fixture.toString()), refused.getMessage());
    }

    @Test
    void theRevisionTheRefusalNamesIsTheOneTheRetryGoesThroughWith() throws IOException {
        final String stale = ConfigFiles.read(fixture).revision();
        ConfigFiles.write(fixture, Map.of("port", ConfigChange.of("9090")), stale);

        final StaleConfigException refused = assertThrows(
                StaleConfigException.class,
                () -> ConfigFiles.write(fixture, Map.of("enabled", ConfigChange.of("false")), stale));

        // Which is what makes the refusal actionable: the operator looks at the file, decides, and saves again.
        final ConfigDocument saved =
                ConfigFiles.write(fixture, Map.of("enabled", ConfigChange.of("false")), refused.actual());

        assertEquals("false", saved.find("enabled").orElseThrow().value());
        assertTrue(
                Files.readString(fixture).contains("port: 9090"), "the retry undid the other admin's change after all");
    }

    @Test
    void aSaveWithNoRevisionAtAllWritesUnconditionallyAndOnlyTestsCanAskForOne() throws IOException {
        // The two-argument write is package-private: an unconditional save must not be reachable from the API.
        ConfigFiles.write(fixture, Map.of("port", ConfigChange.of("9091")));
        assertTrue(Files.readString(fixture).contains("port: 9091"));

        ConfigFiles.write(fixture, Map.of("port", ConfigChange.of("9092")), null);
        assertTrue(Files.readString(fixture).contains("port: 9092"));
    }

    @Test
    void aRevisionThatIsNotARevisionIsRefusedNotTreatedAsNoRevisionAtAll() throws IOException {
        // A client sending "" or "null" or "undefined" must not reach the unconditional branch; only a Java null does.
        for (final String nonsense : new String[] {"", "   ", "undefined", "null", "0"}) {
            assertThrows(
                    StaleConfigException.class,
                    () -> ConfigFiles.write(fixture, Map.of("port", ConfigChange.of("9099")), nonsense),
                    nonsense);
        }
        assertTrue(Files.readString(fixture).contains("port: 8080"));
    }

    @Test
    void theRevisionIsShortHexadecimalAndMadeOfTheFilesUtf8Bytes() throws IOException {
        // A truncated SHA-256 is worth pinning: a future digest change would change what the frontend stores.
        final String revision = ConfigFiles.read(fixture).revision();

        assertEquals(16, revision.length(), revision);
        assertTrue(revision.matches("[0-9a-f]{16}"), revision);
        assertEquals(revision, ConfigFiles.revisionOf(Files.readString(fixture)));
        assertEquals(revision, ConfigFiles.revisionOf(new String(Files.readAllBytes(fixture), StandardCharsets.UTF_8)));
    }
}
