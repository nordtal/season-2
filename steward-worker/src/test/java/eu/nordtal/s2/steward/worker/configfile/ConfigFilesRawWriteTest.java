package eu.nordtal.s2.steward.worker.configfile;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link ConfigFiles#writeRaw} - the raw editor's own save (steward/60).
 *
 * <p>Everything here is asserted on the bytes of the file, the same discipline
 * {@code ConfigFilesWriteTest} and {@code ConfigFilesRevisionTest} hold the parsed path to: a raw
 * save that reformatted, re-encoded or otherwise touched a byte it was not asked to touch would
 * defeat the one thing this path exists for.</p>
 */
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
    @DisplayName("what was typed is exactly what lands on disk")
    void writesVerbatim() throws IOException {
        final String typed = "# a hand edit\none = uno\nnot even valid = properties = at = all\n";
        ConfigFiles.writeRaw(fixture, typed, null);
        assertEquals(typed, Files.readString(fixture, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("nothing is ever refused for what the content says - even nonsense saves")
    void nonsenseIsNotRefused() throws IOException {
        final String nonsense = "{{{ this is not any format at all ]]]";
        ConfigFiles.writeRaw(fixture, nonsense, null);
        assertEquals(nonsense, Files.readString(fixture, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("an empty file may be written, on purpose")
    void emptyContentIsAccepted() throws IOException {
        ConfigFiles.writeRaw(fixture, "", null);
        assertEquals("", Files.readString(fixture, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("the returned revision matches what a fresh read now says")
    void returnedRevisionMatchesAFreshRead() throws IOException {
        final String written = ConfigFiles.writeRaw(fixture, "one=uno\n", null);
        // A .properties file has no ConfigFiles.read() of its own kind - revisionOf is format
        // agnostic, so it is asked directly of the bytes now on disk.
        final String rereadRevision =
                ConfigFiles.revisionOf(Files.readString(fixture, StandardCharsets.UTF_8));
        assertEquals(rereadRevision, written);
    }

    @Test
    @DisplayName("a write moves the revision")
    void aWriteMovesTheRevision() throws IOException {
        final String before = ConfigFiles.revisionOf(Files.readString(fixture, StandardCharsets.UTF_8));
        final String after = ConfigFiles.writeRaw(fixture, "one=uno\ntwo=2\n", null);
        assertNotEquals(before, after);
    }

    // -------------------------------------------------------------------------------------------
    // The stale check - exactly the parsed path's own guarantee (steward/56, steward/60)
    // -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a save against the revision the file still has succeeds")
    void matchingRevisionSucceeds() throws IOException {
        final String revision = ConfigFiles.revisionOf(Files.readString(fixture, StandardCharsets.UTF_8));
        ConfigFiles.writeRaw(fixture, "one=uno\n", revision);
        assertEquals("one=uno\n", Files.readString(fixture, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("a save against a stale revision is refused, and nothing is written")
    void staleRevisionIsRefused() throws IOException {
        final String stale = ConfigFiles.revisionOf(Files.readString(fixture, StandardCharsets.UTF_8));
        // Somebody else writes it first.
        Files.writeString(fixture, "one=somebody-else\ntwo=2\n", StandardCharsets.UTF_8);

        final StaleConfigException thrown = assertThrows(StaleConfigException.class,
                () -> ConfigFiles.writeRaw(fixture, "one=this-should-not-land\n", stale));
        assertEquals(stale, thrown.expected());

        // Nothing this call asked for reached the file - the other admin's write is still there.
        assertEquals("one=somebody-else\ntwo=2\n", Files.readString(fixture, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("a null expected revision writes unconditionally, exactly like the parsed path")
    void nullRevisionSkipsTheCheck() throws IOException {
        Files.writeString(fixture, "changed=underneath\n", StandardCharsets.UTF_8);
        ConfigFiles.writeRaw(fixture, "written=anyway\n", null);
        assertEquals("written=anyway\n", Files.readString(fixture, StandardCharsets.UTF_8));
    }
}
