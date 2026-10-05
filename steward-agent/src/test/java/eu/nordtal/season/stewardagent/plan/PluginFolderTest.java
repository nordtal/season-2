package eu.nordtal.season.stewardagent.plan;

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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link PluginFolder}, the name of the directory a removal deletes.
 *
 * Neither the filename nor a nested {@code name:} may decide it, since the wrong answer deletes the wrong directory.
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
    void theFolderIsTheDescriptorsNameNotTheJars() throws IOException {
        // The jar is voicechat-bukkit-<version>.jar and the folder is plugins/voicechat/: name, not filename.
        final Path path = jar(
                "voicechat-bukkit-2.6.24.jar",
                Map.of("plugin.yml", "name: voicechat\nversion: 2.6.24\nmain: de.maxhenkel.voicechat.Voicechat\n"));

        assertEquals("voicechat", PluginFolder.nameIn(path));
    }

    @Test
    void paperPluginYmlIsPreferredBecauseThatIsTheOneTheServerReads() throws IOException {
        final Path path = jar(
                "both-1.0.0.jar",
                Map.of(
                        "paper-plugin.yml", "name: NewName\n",
                        "plugin.yml", "name: OldName\n"));

        assertEquals("NewName", PluginFolder.nameIn(path));
    }

    @Test
    void aNameNestedUnderCommandsOrLibrariesIsNotThePluginsName() throws IOException {
        // Nested keys come first deliberately: a matcher taking the first name: it finds must not pass by accident.
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
    void aJarWithNoDescriptorAnswersNullAndNullMustNeverBecomeADeletion() throws IOException {
        assertNull(PluginFolder.nameIn(jar("empty-1.0.0.jar", Map.of("META-INF/MANIFEST.MF", "\n"))));
    }

    @Test
    void aFileThatIsNotAJarAtAllAnswersNullRatherThanThrowing() throws IOException {
        final Path path = directory.resolve("broken-1.0.0.jar");
        Files.writeString(path, "this is not a zip");

        // The caller is a page listing ten plugins; one unreadable jar must not empty the list.
        assertNull(PluginFolder.nameIn(path));
    }
}
