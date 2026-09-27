package eu.nordtal.s2.steward.worker.configfile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link ConfigFiles#writeRaw}, the raw editor's save, asserted on the bytes of the file. */
class ConfigFilesRawWriteTest {

    @TempDir
    Path directory;

    private Path fixture;

    @BeforeEach
    void writeFixture() throws IOException {
        fixture = directory.resolve("fixture.properties");
        Files.writeString(fixture, "one=1\ntwo=2\n", StandardCharsets.UTF_8);
    }

    @Test
    void whatWasTypedIsExactlyWhatLandsOnDisk() throws IOException {
        final String typed = "# a hand edit\none = uno\nnot even valid = properties = at = all\n";
        ConfigFiles.writeRaw(fixture, typed, null);
        assertEquals(typed, Files.readString(fixture, StandardCharsets.UTF_8));
    }

    @Test
    void nothingIsEverRefusedForWhatTheContentSaysEvenNonsenseSaves() throws IOException {
        final String nonsense = "{{{ this is not any format at all ]]]";
        ConfigFiles.writeRaw(fixture, nonsense, null);
        assertEquals(nonsense, Files.readString(fixture, StandardCharsets.UTF_8));
    }

    @Test
    void anEmptyFileMayBeWrittenOnPurpose() throws IOException {
        ConfigFiles.writeRaw(fixture, "", null);
        assertEquals("", Files.readString(fixture, StandardCharsets.UTF_8));
    }

    @Test
    void theReturnedRevisionMatchesWhatAFreshReadNowSays() throws IOException {
        final String written = ConfigFiles.writeRaw(fixture, "one=uno\n", null);
        // A .properties file has no ConfigFiles.read(); revisionOf is format agnostic, so it is asked directly.
        final String rereadRevision = ConfigFiles.revisionOf(Files.readString(fixture, StandardCharsets.UTF_8));
        assertEquals(rereadRevision, written);
    }

    @Test
    void aWriteMovesTheRevision() throws IOException {
        final String before = ConfigFiles.revisionOf(Files.readString(fixture, StandardCharsets.UTF_8));
        final String after = ConfigFiles.writeRaw(fixture, "one=uno\ntwo=2\n", null);
        assertNotEquals(before, after);
    }

    // The stale check, the parsed path's own guarantee

    @Test
    void aSaveAgainstTheRevisionTheFileStillHasSucceeds() throws IOException {
        final String revision = ConfigFiles.revisionOf(Files.readString(fixture, StandardCharsets.UTF_8));
        ConfigFiles.writeRaw(fixture, "one=uno\n", revision);
        assertEquals("one=uno\n", Files.readString(fixture, StandardCharsets.UTF_8));
    }

    @Test
    void aSaveAgainstAStaleRevisionIsRefusedAndNothingIsWritten() throws IOException {
        final String stale = ConfigFiles.revisionOf(Files.readString(fixture, StandardCharsets.UTF_8));
        // Somebody else writes it first.
        Files.writeString(fixture, "one=somebody-else\ntwo=2\n", StandardCharsets.UTF_8);

        final StaleConfigException thrown = assertThrows(
                StaleConfigException.class, () -> ConfigFiles.writeRaw(fixture, "one=this-should-not-land\n", stale));
        assertEquals(stale, thrown.expected());

        // Nothing this call asked for reached the file; the other admin's write is still there.
        assertEquals("one=somebody-else\ntwo=2\n", Files.readString(fixture, StandardCharsets.UTF_8));
    }

    @Test
    void aNullExpectedRevisionWritesUnconditionallyExactlyLikeTheParsedPath() throws IOException {
        Files.writeString(fixture, "changed=underneath\n", StandardCharsets.UTF_8);
        ConfigFiles.writeRaw(fixture, "written=anyway\n", null);
        assertEquals("written=anyway\n", Files.readString(fixture, StandardCharsets.UTF_8));
    }
}
