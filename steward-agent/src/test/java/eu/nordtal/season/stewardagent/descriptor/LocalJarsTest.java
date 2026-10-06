package eu.nordtal.season.stewardagent.descriptor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The jars a service's volume holds that were built outside a release, which an update run would overwrite. */
class LocalJarsTest {

    @TempDir
    Path volumes;

    @Test
    void aJarWhoseDescriptorSaysLocalIsNamedAndAReleasedOneIsNot() throws IOException {
        writeJar(volumes.resolve("smp/plugins/smp-0.17.0.jar"), "{\"id\": \"smp\", \"local\": true}");
        writeJar(volumes.resolve("smp/plugins/display-tags-0.17.0.jar"), "{\"id\": \"display-tags\"}");
        writeJar(volumes.resolve("smp/.server/paper-1.21.jar"), null);

        assertEquals(List.of("smp-0.17.0.jar"), new LocalJars(volumes).of("smp"));
    }

    @Test
    void aServerJarBuiltHereCountsToo() throws IOException {
        writeJar(volumes.resolve("limbo/.server/limbo-0.17.0.jar"), "{\"id\": \"limbo\", \"local\": true}");

        assertEquals(List.of("limbo-0.17.0.jar"), new LocalJars(volumes).of("limbo"));
    }

    @Test
    void aJarReplacedInPlaceIsReadAgain() throws IOException {
        final Path jar = volumes.resolve("smp/plugins/smp-0.17.0.jar");
        writeJar(jar, "{\"id\": \"smp\", \"local\": true}");
        final LocalJars jars = new LocalJars(volumes);
        assertEquals(List.of("smp-0.17.0.jar"), jars.of("smp"));

        writeJar(jar, "{\"id\": \"smp\", \"name\": \"SMP, as released\"}");
        Files.setLastModifiedTime(
                jar, FileTime.fromMillis(Files.getLastModifiedTime(jar).toMillis() + 2000));

        assertEquals(List.of(), jars.of("smp"), "the release's copy over the local one is no local build any more");
    }

    @Test
    void noVolumeOrNoMountIsNoLocalBuild() {
        assertEquals(List.of(), new LocalJars(volumes).of("discord-bot"));
        assertEquals(List.of(), new LocalJars(null).of("smp"));
    }

    private static void writeJar(final Path jar, final @Nullable String descriptor) throws IOException {
        Files.createDirectories(jar.getParent());
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry(descriptor == null ? "plugin.yml" : PluginDescriptors.ENTRY));
            out.write((descriptor == null ? "name: Paper\n" : descriptor).getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
    }
}
