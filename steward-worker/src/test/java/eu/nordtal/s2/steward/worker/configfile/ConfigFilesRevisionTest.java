package eu.nordtal.s2.steward.worker.configfile;

import eu.nordtal.jcore.config.ConfigLoader;
import eu.nordtal.jcore.config.exception.ConfigException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The revision - the thing that stops the second of two open forms silently undoing the first.
 *
 * <h2>What is actually being defended</h2>
 * Not a conflict, a <em>disappearance</em>. Two admins open the same file; the first changes the
 * port and saves; the second, whose form still shows the old port, changes the token and saves.
 * Without a revision the second save re-reads the file, applies its own one change to what it
 * finds, and writes the result - which is correct in every respect except that the first admin's
 * port is back to what it was, with nothing anywhere recording that it ever moved. Nobody gets an
 * error, nobody gets a conflict, and the only evidence is a service that starts on the wrong port
 * some days later.
 *
 * <p>The assertions below are on the <b>bytes of the file</b> wherever a file is involved, for the
 * same reason {@code ConfigFilesWriteTest} is: a refused save that nevertheless rewrote the file -
 * reformatted, comments dropped, quoting changed - would pass every test that re-parses.</p>
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

    // -------------------------------------------------------------------------------------------
    // What a revision is a revision of
    // -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a file that is written says something different afterwards")
    void aWriteMovesTheRevision() throws IOException {
        final String before = ConfigFiles.read(fixture).revision();

        final ConfigDocument after = ConfigFiles.write(fixture,
                Map.of("port", ConfigChange.of("9090")), before);

        assertNotEquals(before, after.revision());
        assertEquals(ConfigFiles.read(fixture).revision(), after.revision(),
                "the revision handed back by the write is not the one a fresh read of the same"
                        + " file produces - so the browser's next save is refused for no reason,"
                        + " and the only way out of it is a reload");
    }

    @Test
    @DisplayName("the same bytes always give the same revision, wherever they are and whenever")
    void identicalContentIsIdenticalRevision() throws IOException {
        // Deliberately two different files, in two different directories, written at two different
        // moments. The revision is not allowed to depend on the path, the inode or the clock: the
        // page compares it across reads of the same file, and a value that changed on its own would
        // make every second save a 409 that nothing can clear.
        final Path elsewhere = Files.createDirectory(directory.resolve("second"))
                .resolve("fixture.yml");
        Files.writeString(elsewhere, Files.readString(fixture));

        assertEquals(ConfigFiles.read(fixture).revision(), ConfigFiles.read(elsewhere).revision());
        assertEquals(ConfigFiles.read(fixture).revision(), ConfigFiles.read(fixture).revision());
    }

    @Test
    @DisplayName("a saved value that changes nothing leaves the revision where it was")
    void anIdenticalSaveIsNotAChange() throws IOException {
        // A form sends back every field, and most saves therefore re-send values nobody touched.
        // If writing the same characters moved the revision, every save would invalidate every
        // other open form in the building - which teaches an operator that the conflict message
        // means nothing and to press through it, which is the end of the mechanism.
        final String before = ConfigFiles.read(fixture).revision();
        final byte[] bytes = Files.readAllBytes(fixture);

        final ConfigDocument after = ConfigFiles.write(fixture,
                Map.of("port", ConfigChange.of("8080")), before);

        assertArrayEquals(bytes, Files.readAllBytes(fixture));
        assertEquals(before, after.revision());
    }

    @Test
    @DisplayName("two contents of the same length are two revisions")
    void lengthIsNotTheRevision() throws IOException {
        // The javadoc on revisionOf rules out the size and the mtime by name, and both of those are
        // the tempting shortcuts. `port: 8080` to `port: 8081` is the ordinary case - one character
        // in place - and it is the case a length or a coarse timestamp cannot see at all.
        final String before = ConfigFiles.read(fixture).revision();
        ConfigFiles.write(fixture, Map.of("port", ConfigChange.of("8081")), before);

        assertEquals(Files.readString(fixture).length(),
                Files.readString(fixture).replace("8081", "8080").length(),
                "the fixture no longer makes this test's point - pick another equal-length edit");
        assertNotEquals(before, ConfigFiles.read(fixture).revision());
    }

    @Test
    @DisplayName("an edit the parser does not care about still moves the revision")
    void theRevisionIsOfTheFileAndNotOfTheModel() throws IOException {
        // Somebody edited the file over SSH, or a container wrote its own defaults back. Neither
        // touches a value, so a revision computed from the parsed document would be unchanged and
        // the next save would write over the edit. The revision is of the characters.
        final String before = ConfigFiles.read(fixture).revision();
        Files.writeString(fixture, Files.readString(fixture) + "\n# added by hand\n");

        assertNotEquals(before, ConfigFiles.read(fixture).revision());
    }

    // -------------------------------------------------------------------------------------------
    // What a stale revision costs
    // -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a save against a revision that has moved on is refused and writes nothing at all")
    void aStaleSaveIsRefusedAndTheFileIsUntouched() throws IOException {
        final String whatTheSecondFormStillShows = ConfigFiles.read(fixture).revision();

        // The first admin saves.
        ConfigFiles.write(fixture, Map.of("port", ConfigChange.of("9090")),
                whatTheSecondFormStillShows);
        final byte[] afterTheFirstSave = Files.readAllBytes(fixture);

        // The second admin saves a different key, from a form drawn before that.
        final StaleConfigException refused = assertThrows(StaleConfigException.class,
                () -> ConfigFiles.write(fixture, Map.of("enabled", ConfigChange.of("false")),
                        whatTheSecondFormStillShows));

        assertArrayEquals(afterTheFirstSave, Files.readAllBytes(fixture),
                "the refused save still changed the file - which is the exact outcome the"
                        + " revision exists to prevent, now with an error message on top of it");
        assertEquals(whatTheSecondFormStillShows, refused.expected());
        assertEquals(ConfigFiles.read(fixture).revision(), refused.actual(),
                "the exception's `actual` is not what the file says, so the message it produces"
                        + " sends whoever reads it looking for a revision that never existed");
        assertTrue(refused.getMessage().contains(fixture.toString()), refused.getMessage());
    }

    @Test
    @DisplayName("the revision the refusal names is the one the retry goes through with")
    void theRetryUsesTheRevisionFromTheRefusal() throws IOException {
        final String stale = ConfigFiles.read(fixture).revision();
        ConfigFiles.write(fixture, Map.of("port", ConfigChange.of("9090")), stale);

        final StaleConfigException refused = assertThrows(StaleConfigException.class,
                () -> ConfigFiles.write(fixture, Map.of("enabled", ConfigChange.of("false")),
                        stale));

        // Which is what makes the refusal actionable rather than a wall: the operator looks at the
        // file as it now stands, decides their change is still the one they want, and saves again.
        final ConfigDocument saved = ConfigFiles.write(fixture,
                Map.of("enabled", ConfigChange.of("false")), refused.actual());

        assertEquals("false", saved.find("enabled").orElseThrow().value());
        assertTrue(Files.readString(fixture).contains("port: 9090"),
                "the retry undid the other admin's change after all");
    }

    @Test
    @DisplayName("a save with no revision at all writes unconditionally, and only tests can ask for one")
    void theUncheckedDoorIsStillThereAndIsPackagePrivate() throws IOException {
        // The two-argument write is package-private on purpose: the whole value of the mechanism is
        // that the API route cannot reach an unconditional save even by forgetting a field, and a
        // public overload that skips the check is a field somebody forgets. This test is in the
        // package, which is the only place it can be called from - if that ever stops being true,
        // this test compiles from somewhere else and nobody notices.
        ConfigFiles.write(fixture, Map.of("port", ConfigChange.of("9091")));
        assertTrue(Files.readString(fixture).contains("port: 9091"));

        ConfigFiles.write(fixture, Map.of("port", ConfigChange.of("9092")), null);
        assertTrue(Files.readString(fixture).contains("port: 9092"));
    }

    @Test
    @DisplayName("a revision that is not a revision is refused, not treated as no revision at all")
    void nonsenseIsNotTheSameAsAbsent() throws IOException {
        // The failure mode this rules out: a client that sends "" or "null" or the string
        // "undefined" - all of which a frontend produces sooner or later - must not land in the
        // unconditional branch. Only a Java null does that, and a Java null cannot come off the
        // wire through ConfigApi, which refuses a missing or blank `revision` with a 400.
        for (final String nonsense : new String[] {"", "   ", "undefined", "null", "0"}) {
            assertThrows(StaleConfigException.class, () -> ConfigFiles.write(fixture,
                    Map.of("port", ConfigChange.of("9099")), nonsense), nonsense);
        }
        assertTrue(Files.readString(fixture).contains("port: 8080"));
    }

    @Test
    @DisplayName("the revision is short, hexadecimal and made of the file's UTF-8 bytes")
    void theShapeOfTheValue() throws IOException {
        // It ends up in a JSON body and in a browser's memory, so it has to survive both without
        // escaping; and it is a truncated SHA-256 of the content, which is a property worth pinning
        // because a future "improvement" to a full digest or to Base64 changes what the frontend
        // stores. Not a security boundary - anybody who can save can read.
        final String revision = ConfigFiles.read(fixture).revision();

        assertEquals(16, revision.length(), revision);
        assertTrue(revision.matches("[0-9a-f]{16}"), revision);
        assertEquals(revision, ConfigFiles.revisionOf(Files.readString(fixture)));
        assertEquals(revision, ConfigFiles.revisionOf(
                new String(Files.readAllBytes(fixture), StandardCharsets.UTF_8)));
    }
}
