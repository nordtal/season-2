package eu.nordtal.s2.updater.apply;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The two lines of {@code pack.yml} the updater owns, and the ones it must not disturb. */
class PackWriterTest {

    private static final String URL =
            "https://github.com/nordtal/season-2/releases/download/v0.2.0/nordtal-resource-pack-0.2.0.zip";
    private static final String SHA1 = "6f1ed002ab5595859014ebf0951522d9d0f2ee34";

    @TempDir
    Path directory;

    Path packYml;

    @BeforeEach
    void writeAFileShapedLikeJcoreWritesIt() throws IOException {
        packYml = directory.resolve("pack.yml");
        Files.writeString(packYml, """
                # -------------------------------------------------------------------
                #   network-control - the resource pack offered in the waiting room
                # -------------------------------------------------------------------

                # Whether a pack is offered at all.
                enabled: true

                # Where the client downloads the pack from.
                # PUT THE github.com/.../releases/download/... URL HERE.
                url: https://github.com/nordtal/season-2/releases/download/v0.1.0/nordtal-resource-pack-0.1.0.zip

                # The SHA-1 of exactly the zip at the url above.
                sha1: 0000000000000000000000000000000000000000

                # Whether the offer is marked as required.
                force: true
                apply-timeout-seconds: 180
                """, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("both values are replaced and every comment survives")
    void writesTheTwoLinesAndNothingElse() throws IOException {
        assertTrue(PackWriter.write(packYml, URL, SHA1));

        final String written = Files.readString(packYml, StandardCharsets.UTF_8);
        assertTrue(written.contains("url: " + URL), written);
        assertTrue(written.contains("sha1: " + SHA1), written);
        // The comments are the reason this file is readable at all, and the reason a whole-file
        // rewrite through a spec this module does not own was rejected.
        assertTrue(written.contains("# PUT THE github.com/.../releases/download/... URL HERE."), written);
        assertTrue(written.contains("enabled: true"), written);
        assertTrue(written.contains("force: true"), written);
        assertTrue(written.contains("apply-timeout-seconds: 180"), written);
    }

    @Test
    @DisplayName("a file that already says this is not touched at all")
    void unchangedMeansUnwritten() throws IOException {
        PackWriter.write(packYml, URL, SHA1);
        final FileTime before = Files.getLastModifiedTime(packYml);
        final String content = Files.readString(packYml);

        assertFalse(PackWriter.write(packYml, URL, SHA1));

        // A rewrite that changes nothing still changes a modification time, and on a config volume
        // that is the difference between "the updater has never touched this" and "something did".
        assertEquals(before, Files.getLastModifiedTime(packYml));
        assertEquals(content, Files.readString(packYml));
    }

    @Test
    @DisplayName("a key that is not there is an error, never an append")
    void refusesAFileItDoesNotUnderstand() throws IOException {
        Files.writeString(packYml, "enabled: true\nforce: true\n", StandardCharsets.UTF_8);

        final IOException failure =
                assertThrows(IOException.class, () -> PackWriter.write(packYml, URL, SHA1));
        assertTrue(failure.getMessage().contains("0 top-level 'url'"), failure.getMessage());
    }

    @Test
    @DisplayName("a nested url: is not a top-level one and is left alone")
    void onlyTopLevelKeysAreTouched() throws IOException {
        Files.writeString(packYml, """
                enabled: true
                url: old
                sha1: old
                something:
                  url: leave-me-alone
                """, StandardCharsets.UTF_8);

        PackWriter.write(packYml, URL, SHA1);

        assertTrue(Files.readString(packYml).contains("  url: leave-me-alone"));
    }

    @Test
    @DisplayName("a volume the proxy has never started against gets a two-key file, not an error")
    void createsTheFileOnAFreshVolume() throws IOException {
        // The deadlock this resolves: the proxy writes pack.yml on its first start, and a proxy
        // with no jar in plugins/ does not start - and filling plugins/ is the updater's job.
        Files.delete(packYml);

        assertTrue(PackWriter.write(packYml, URL, SHA1));

        final String written = Files.readString(packYml, StandardCharsets.UTF_8);
        assertTrue(written.contains("url: " + URL), written);
        assertTrue(written.contains("sha1: " + SHA1), written);
        // Only the two keys this module owns. jcore adds enabled, force and
        // apply-timeout-seconds on the proxy's first load, and it is jcore's business what their
        // defaults are.
        assertFalse(written.contains("enabled:"), written);
        assertFalse(written.contains("force:"), written);
    }

    @Test
    @DisplayName("a file that exists but is missing a key is still an error")
    void createIsNotAnExcuseToAppend() throws IOException {
        // Creating a whole file and patching a broken one are different decisions. A pack.yml
        // carrying three of five keys is one nothing here understands.
        Files.writeString(packYml, "enabled: true\nurl: old\n", StandardCharsets.UTF_8);

        final IOException failure =
                assertThrows(IOException.class, () -> PackWriter.write(packYml, URL, SHA1));
        assertTrue(failure.getMessage().contains("0 top-level 'sha1'"), failure.getMessage());
    }

    @Test
    @DisplayName("the parent directory is created with the file")
    void createsThePluginDataDirectoryToo() throws IOException {
        final Path nested = directory.resolve("plugins/network-control/pack.yml");

        assertTrue(PackWriter.write(nested, URL, SHA1));
        assertTrue(Files.isRegularFile(nested));
    }

    @Test
    @DisplayName("no temporary file is left behind")
    void cleansUpAfterItself() throws IOException {
        PackWriter.write(packYml, URL, SHA1);

        try (var entries = Files.list(directory)) {
            assertEquals(1, entries.count());
        }
    }
}
