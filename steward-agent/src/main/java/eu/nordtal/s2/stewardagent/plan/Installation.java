package eu.nordtal.s2.stewardagent.plan;

import eu.nordtal.s2.internalapi.agent.JarName;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * What is lying in one service's volume: the jars in {@code plugins/} and the server jar in {@code .server/}.
 *
 * Disk is the only truth. A missing mount is reported as {@link #absent}, never created or read as empty.
 */
public record Installation(String service, Path directory, boolean mounted, List<Jar> plugins, List<Jar> serverJars) {

    /** Where a service keeps its plugin jars, relative to the volume root. */
    public static final String PLUGINS = "plugins";

    /** Where {@code entrypoint.sh} caches the server jar. */
    public static final String SERVER_CACHE = ".server";

    /** One jar on disk. */
    public record Jar(Path path, String fileName) {

        public @Nullable String prefix() {
            return JarName.prefixOf(fileName);
        }

        public @Nullable String version() {
            return JarName.versionOf(fileName);
        }
    }

    public static Installation absent(final String service, final Path directory) {
        return new Installation(service, directory, false, List.of(), List.of());
    }

    /** Reads one service directory, only its regular files, and never writes. */
    public static Installation scan(final String service, final Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return absent(service, directory);
        }
        return new Installation(
                service, directory, true, jarsIn(directory.resolve(PLUGINS)), jarsIn(directory.resolve(SERVER_CACHE)));
    }

    /** The installed jar whose prefix matches {@code fileName}'s, or {@code null}. */
    public @Nullable Jar matching(final String fileName) {
        final String prefix = JarName.prefixOf(fileName);
        return prefix == null ? null : withPrefix(prefix);
    }

    /** The installed jar whose filename prefix is {@code prefix}, or {@code null}. */
    public @Nullable Jar withPrefix(final String prefix) {
        for (final Jar jar : plugins) {
            if (prefix.equals(jar.prefix())) {
                return jar;
            }
        }
        for (final Jar jar : serverJars) {
            if (prefix.equals(jar.prefix())) {
                return jar;
            }
        }
        return null;
    }

    private static List<Jar> jarsIn(final Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        final List<Jar> jars = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (final Path entry : entries) {
                final String name = entry.getFileName().toString();
                // .partial files are an interrupted download, not jars.
                if (JarName.isJar(name) && Files.isRegularFile(entry)) {
                    jars.add(new Jar(entry, name));
                }
            }
        }
        jars.sort(Comparator.comparing(Jar::fileName));
        return List.copyOf(jars);
    }
}
