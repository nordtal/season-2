package eu.nordtal.season.stewardagent.descriptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.internalapi.agent.AgentWire;
import eu.nordtal.season.stewardagent.bundles.ImageJars;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The descriptors read out of the jars beside each service's data, and out of the images of the rest. */
class PluginDescriptorsTest {

    private static final byte[] LOGO = {(byte) 0x89, 'P', 'N', 'G'};

    @TempDir
    Path configs;

    @TempDir
    Path images;

    private final List<String> asked = new ArrayList<>();

    private ImageJars imageJars() {
        return service -> {
            asked.add(service);
            final Path jar = images.resolve(service + ".jar");
            return Files.isRegularFile(jar) ? jar : null;
        };
    }

    @Test
    void aPluginsDescriptorIsReadFromItsJarInThePluginsFolder() throws IOException {
        writeJar(configs.resolve("smp/smp-0.11.0.jar"), descriptor("smp", "SMP", "{\"milestones\": \"milestones\"}"));
        writeJar(
                configs.resolve("smp/Chunky-1.4.jar"),
                Map.of("plugin.yml", "name: Chunky\n".getBytes(StandardCharsets.UTF_8)));

        final List<AgentWire.Descriptor> found =
                new PluginDescriptors(configs, imageJars(), () -> services("smp")).read();

        assertEquals(1, found.size(), found.toString());
        final AgentWire.Descriptor smp = found.getFirst();
        assertEquals("smp", smp.service());
        assertEquals("smp", smp.id());
        assertEquals("SMP", smp.name());
        assertEquals(Map.of("milestones", "milestones"), smp.editors());
        assertEquals("data:image/png;base64,iVBORw==", smp.logo());
    }

    @Test
    void aServiceWithNoJarBesideItsDataIsReadFromItsImage() throws IOException {
        Files.createDirectories(configs.resolve("discord-bot"));
        writeJar(images.resolve("discord-bot.jar"), descriptor("discord-bot", "Discord bot", "{}"));

        final List<AgentWire.Descriptor> found =
                new PluginDescriptors(configs, imageJars(), () -> services("discord-bot", "postgres")).read();

        assertEquals(
                List.of("discord-bot"),
                found.stream().map(AgentWire.Descriptor::id).toList());
    }

    @Test
    void anImageThatCarriesNoDescriptorIsAskedOnce() throws IOException {
        final PluginDescriptors descriptors = new PluginDescriptors(configs, imageJars(), () -> services("postgres"));

        descriptors.read();
        descriptors.read();

        assertEquals(List.of("postgres"), asked);
    }

    @Test
    void aNewReleaseOfAnImageIsAskedAgain() throws IOException {
        final List<PluginDescriptors.Service> running =
                new ArrayList<>(List.of(new PluginDescriptors.Service("steward", "ghcr.io/nordtal/steward:0.10.3")));
        final PluginDescriptors descriptors = new PluginDescriptors(configs, imageJars(), () -> running);
        descriptors.read();

        writeJar(images.resolve("steward.jar"), descriptor("steward", "Steward", "{}"));
        running.set(0, new PluginDescriptors.Service("steward", "ghcr.io/nordtal/steward:0.11.0"));

        assertEquals(
                List.of("steward"),
                descriptors.read().stream().map(AgentWire.Descriptor::id).toList());
    }

    @Test
    void oneJarOnTwoServicesIsOneDescriptorUnderItsOwnService() throws IOException {
        // migrate runs steward-agent's image, so both carry the same descriptor.
        writeJar(images.resolve("migrate.jar"), descriptor("steward-agent", "Steward agent", "{}"));
        writeJar(images.resolve("steward-agent.jar"), descriptor("steward-agent", "Steward agent", "{}"));

        final List<AgentWire.Descriptor> found =
                new PluginDescriptors(configs, imageJars(), () -> services("migrate", "steward-agent")).read();

        assertEquals(1, found.size(), found.toString());
        assertEquals("steward-agent", found.getFirst().service());
    }

    @Test
    void aDescriptorThatNamesNoLogoItCarriesHasNone() throws IOException {
        writeJar(
                configs.resolve("limbo/limbo-0.11.0.jar"),
                Map.of(
                        PluginDescriptors.ENTRY,
                        "{\"id\": \"limbo\", \"name\": \"Limbo\", \"logo\": \"nordtal/logo.png\", \"editors\": {}}"
                                .getBytes(StandardCharsets.UTF_8)));

        final AgentWire.Descriptor limbo = new PluginDescriptors(configs, imageJars(), () -> services("limbo"))
                .read()
                .getFirst();

        assertNull(limbo.logo());
        assertTrue(limbo.editors().isEmpty());
    }

    private static List<PluginDescriptors.Service> services(final String... names) {
        return java.util.Arrays.stream(names)
                .map(name -> new PluginDescriptors.Service(name, "ghcr.io/nordtal/" + name + ":0.11.0"))
                .toList();
    }

    private static Map<String, byte[]> descriptor(final String id, final String name, final String editors) {
        return Map.of(
                PluginDescriptors.ENTRY,
                ("{\"id\": \"" + id + "\", \"name\": \"" + name + "\", \"logo\": \"nordtal/logo.png\", \"editors\": "
                                + editors + "}")
                        .getBytes(StandardCharsets.UTF_8),
                "nordtal/logo.png",
                LOGO);
    }

    private static void writeJar(final Path jar, final Map<String, byte[]> entries) throws IOException {
        Files.createDirectories(jar.getParent());
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (final Map.Entry<String, byte[]> entry : entries.entrySet()) {
                out.putNextEntry(new JarEntry(entry.getKey()));
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
    }
}
