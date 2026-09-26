package eu.nordtal.s2.steward.worker.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link PluginFolder} - the name of the directory a removal deletes (season-2-ops/129).
 *
 * <p>The reason this is worth a test of its own: the answer is used to delete a directory, and
 * the wrong answer deletes the wrong one. The two failures that matter are both here - guessing
 * from the filename (which is wrong for every plugin whose jar is not named after itself) and
 * matching a nested {@code name:}, of which every plugin descriptor has several.</p>
 */
class PluginFolderTest {

    @TempDir
    Path directory;

    private Path jar(final String name, final Map<String, String> entries) throws IOException {
        final Path path = directory.resolve(name);
        try (OutputStream out = Files.newOutputStream(path);
                ZipOutputStream zip = new ZipOutputStream(out)) {
            for (final Map.Entry<String, String> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return path;
    }

    @Test
    @DisplayName("the folder is the descriptor's name, not the jar's")
    void readsTheDescriptorAndNotTheFilename() throws IOException {
        // Simple Voice Chat's real shape: the jar is voicechat-bukkit-<version>.jar and the folder
        // is plugins/voicechat/. Anything deriving the folder from the filename deletes nothing, or
        // something else.
        final Path path = jar(
                "voicechat-bukkit-2.6.24.jar",
                Map.of("plugin.yml", "name: voicechat\nversion: 2.6.24\nmain: de.maxhenkel.voicechat.Voicechat\n"));

        assertEquals("voicechat", PluginFolder.nameIn(path));
    }

    @Test
    @DisplayName("paper-plugin.yml is preferred, because that is the one the server reads")
    void prefersThePaperDescriptor() throws IOException {
        final Path path = jar(
                "both-1.0.0.jar",
                Map.of(
                        "paper-plugin.yml", "name: NewName\n",
                        "plugin.yml", "name: OldName\n"));

        assertEquals("NewName", PluginFolder.nameIn(path));
    }

    @Test
    @DisplayName("a name: nested under commands or libraries is not the plugin's name")
    void ignoresNestedNames() throws IOException {
        // The nested ones come FIRST, deliberately. YAML does not care about key order and real
        // descriptors vary, and a matcher that simply takes the first `name:` it finds passes a
        // fixture where the plugin's own name happens to be at the top - which is how this rule
        // would have been "proved" by a test that could not fail.
        final Path path = jar("thing-1.0.0.jar", Map.of("plugin.yml", """
                api-version: '1.21'
                commands:
                  spawn:
                    name: notThis
                libraries:
                  - name: notThisEither
                name: Actual
                """));

        assertEquals("Actual", PluginFolder.nameIn(path));
    }

    @Test
    @DisplayName("a jar with no descriptor answers null, and null must never become a deletion")
    void noDescriptorIsNoAnswer() throws IOException {
        assertNull(PluginFolder.nameIn(jar("empty-1.0.0.jar", Map.of("META-INF/MANIFEST.MF", "\n"))));
    }

    @Test
    @DisplayName("a file that is not a jar at all answers null rather than throwing")
    void rubbishIsNotAnError() throws IOException {
        final Path path = directory.resolve("broken-1.0.0.jar");
        Files.writeString(path, "this is not a zip");

        // The caller is a page listing ten plugins; one unreadable jar must not empty the list.
        assertNull(PluginFolder.nameIn(path));
    }
}
