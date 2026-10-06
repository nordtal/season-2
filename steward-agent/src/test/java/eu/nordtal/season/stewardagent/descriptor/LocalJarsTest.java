package eu.nordtal.season.stewardagent.descriptor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.stewardagent.PluginJars;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The jars a service's volume holds that were built outside a release, which an update run would overwrite. */
class LocalJarsTest {

    @TempDir
    Path volumes;

    @Test
    void aJarWhoseDescriptorSaysLocalIsNamedAndAReleasedOneIsNot() throws IOException {
        PluginJars.write(volumes.resolve("smp/plugins/smp-0.17.0.jar"), "{\"id\": \"smp\", \"local\": true}", Map.of());
        PluginJars.write(
                volumes.resolve("smp/plugins/display-tags-0.17.0.jar"), "{\"id\": \"display-tags\"}", Map.of());
        PluginJars.foreign(volumes.resolve("smp/.server/paper-1.21.jar"), Map.of("plugin.yml", "name: Paper\n"));

        assertEquals(List.of("smp-0.17.0.jar"), new LocalJars(volumes).of("smp"));
    }

    @Test
    void aServerJarBuiltHereCountsToo() throws IOException {
        PluginJars.write(
                volumes.resolve("limbo/.server/limbo-0.17.0.jar"), "{\"id\": \"limbo\", \"local\": true}", Map.of());

        assertEquals(List.of("limbo-0.17.0.jar"), new LocalJars(volumes).of("limbo"));
    }

    @Test
    void aJarReplacedInPlaceIsReadAgain() throws IOException {
        final Path jar = volumes.resolve("smp/plugins/smp-0.17.0.jar");
        PluginJars.write(jar, "{\"id\": \"smp\", \"local\": true}", Map.of());
        final LocalJars jars = new LocalJars(volumes);
        assertEquals(List.of("smp-0.17.0.jar"), jars.of("smp"));

        PluginJars.write(jar, "{\"id\": \"smp\", \"name\": \"SMP, as released\"}", Map.of());
        Files.setLastModifiedTime(
                jar, FileTime.fromMillis(Files.getLastModifiedTime(jar).toMillis() + 2000));

        assertEquals(List.of(), jars.of("smp"), "the release's copy over the local one is no local build any more");
    }

    @Test
    void noVolumeOrNoMountIsNoLocalBuild() {
        assertEquals(List.of(), new LocalJars(volumes).of("discord-bot"));
        assertEquals(List.of(), new LocalJars(null).of("smp"));
    }
}
