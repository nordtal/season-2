package eu.nordtal.season.stewardagent;

import eu.nordtal.season.stewardagent.descriptor.PluginDescriptors;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/** A jar of ours as the build writes it: its {@code nordtal-plugin.json} first, then whatever the test puts in. */
public final class PluginJars {

    private PluginJars() {}

    /**
     * Writes the jar, its parent directories included.
     *
     * @param descriptor the descriptor's JSON, as {@code nordtal.plugin-descriptor} writes it
     * @param entries entry name to UTF-8 text content
     */
    public static void write(final Path jar, final String descriptor, final Map<String, String> entries)
            throws IOException {
        Files.createDirectories(jar.getParent());
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry(PluginDescriptors.ENTRY));
            out.write(descriptor.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            for (final Map.Entry<String, String> entry : entries.entrySet()) {
                out.putNextEntry(new JarEntry(entry.getKey()));
                out.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
    }

    /** The SMP plugin's jar, whose server follows the message overrides, carrying {@code entries}. */
    public static void smp(final Path jar, final Map<String, String> entries) throws IOException {
        write(jar, "{\"id\": \"smp\", \"name\": \"SMP\", \"editors\": {}, \"messages\": true}", entries);
    }
}
