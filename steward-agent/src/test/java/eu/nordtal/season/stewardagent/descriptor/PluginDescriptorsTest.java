package eu.nordtal.season.stewardagent.descriptor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.common.json.Json;
import eu.nordtal.season.internalapi.agent.AgentWire;
import eu.nordtal.season.stewardagent.bundles.ImageJars;
import eu.nordtal.season.stewardagent.bundles.MessageBundleLocation;
import eu.nordtal.season.stewardagent.bundles.MessageBundles;
import eu.nordtal.season.stewardagent.bundles.ServiceJar;
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
        writeJar(configs.resolve("smp/smp-0.11.0.jar"), descriptor("smp", "{\"milestones\": \"milestones\"}"));
        writeJar(
                configs.resolve("smp/Chunky-1.4.jar"),
                Map.of("plugin.yml", "name: Chunky\n".getBytes(StandardCharsets.UTF_8)));

        final List<AgentWire.Descriptor> found =
                new PluginDescriptors(configs, imageJars(), () -> services("smp")).read();

        assertEquals(1, found.size(), found.toString());
        final AgentWire.Descriptor smp = found.getFirst();
        assertEquals("smp", smp.service());
        assertEquals("smp", smp.id());
        assertEquals(Map.of("milestones", "milestones"), smp.editors());
    }

    @Test
    void aServiceWithNoJarBesideItsDataIsReadFromItsImage() throws IOException {
        Files.createDirectories(configs.resolve("discord-bot"));
        writeJar(images.resolve("discord-bot.jar"), descriptor("discord-bot", "{}"));

        final List<AgentWire.Descriptor> found =
                new PluginDescriptors(configs, imageJars(), () -> services("discord-bot", "postgres")).read();

        assertEquals(
                List.of("discord-bot"),
                found.stream().map(AgentWire.Descriptor::id).toList());
    }

    /** The bot has no folder beside its data, so its bundles are found in its image as its descriptor is. */
    @Test
    void theBundlesAreReadFromTheJarsTheDescriptorsAreFoundIn() throws IOException {
        final Map<String, byte[]> bot = new java.util.HashMap<>(descriptor("discord-bot", "{}", true));
        bot.put("messages/access/en.properties", "contribution.title=Access\n".getBytes(StandardCharsets.UTF_8));
        writeJar(images.resolve("discord-bot.jar"), bot);
        final Map<String, byte[]> smp = new java.util.HashMap<>(descriptor("smp", "{}", true));
        smp.put("messages/smp/en.properties", "welcome=Welcome\n".getBytes(StandardCharsets.UTF_8));
        writeJar(configs.resolve("smp/smp-0.11.0.jar"), smp);
        final Map<String, byte[]> steward = new java.util.HashMap<>(descriptor("steward", "{}"));
        steward.put("messages/steward/en.properties", "title=Steward\n".getBytes(StandardCharsets.UTF_8));
        writeJar(images.resolve("steward.jar"), steward);

        final List<MessageBundleLocation> found = MessageBundles.discover(
                new PluginDescriptors(configs, imageJars(), () -> services("discord-bot", "smp", "steward", "postgres"))
                        .jars());

        assertEquals(
                List.of(
                        new MessageBundleLocation("discord-bot", "", images.resolve("discord-bot.jar")),
                        new MessageBundleLocation("smp", "smp", configs.resolve("smp/smp-0.11.0.jar"))),
                found);
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

        writeJar(images.resolve("steward.jar"), descriptor("steward", "{}"));
        running.set(0, new PluginDescriptors.Service("steward", "ghcr.io/nordtal/steward:0.11.0"));

        assertEquals(
                List.of("steward"),
                descriptors.read().stream().map(AgentWire.Descriptor::id).toList());
    }

    @Test
    void oneJarOnTwoServicesIsOneDescriptorUnderItsOwnService() throws IOException {
        // migrate runs steward-agent's image, so both carry the same descriptor.
        writeJar(images.resolve("migrate.jar"), descriptor("steward-agent", "{}"));
        writeJar(images.resolve("steward-agent.jar"), descriptor("steward-agent", "{}"));

        final List<AgentWire.Descriptor> found =
                new PluginDescriptors(configs, imageJars(), () -> services("migrate", "steward-agent")).read();

        assertEquals(1, found.size(), found.toString());
        assertEquals("steward-agent", found.getFirst().service());
    }

    /** A jar of an older release in a plugins volume still carries a name and a logo, which Steward never reads. */
    @Test
    void anOlderJarsNameAndLogoAreReadPastAndNotPassedOn() throws IOException {
        writeJar(
                configs.resolve("smp/smp-0.11.0.jar"),
                Map.of(
                        PluginDescriptors.ENTRY,
                        ("{\"id\": \"smp\", \"name\": \"SMP\", \"logo\": \"nordtal/logo.png\", "
                                        + "\"editors\": {\"milestones\": \"milestones\"}, \"messages\": true}")
                                .getBytes(StandardCharsets.UTF_8),
                        "nordtal/logo.png",
                        new byte[] {(byte) 0x89, 'P', 'N', 'G'}));

        final PluginDescriptors descriptors = new PluginDescriptors(configs, imageJars(), () -> services("smp"));

        assertEquals(
                "[{\"service\":\"smp\",\"id\":\"smp\",\"editors\":{\"milestones\":\"milestones\"}}]",
                Json.encode(descriptors.read()));
        assertEquals(
                List.of(true),
                descriptors.jars().stream().map(ServiceJar::followsMessages).toList());
    }

    private static List<PluginDescriptors.Service> services(final String... names) {
        return java.util.Arrays.stream(names)
                .map(name -> new PluginDescriptors.Service(name, "ghcr.io/nordtal/" + name + ":0.11.0"))
                .toList();
    }

    private static Map<String, byte[]> descriptor(final String id, final String editors) {
        return descriptor(id, editors, false);
    }

    private static Map<String, byte[]> descriptor(
            final String id, final String editors, final boolean followsMessages) {
        return Map.of(
                PluginDescriptors.ENTRY,
                ("{\"id\": \"" + id + "\", \"editors\": " + editors + ", \"messages\": " + followsMessages + "}")
                        .getBytes(StandardCharsets.UTF_8));
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
