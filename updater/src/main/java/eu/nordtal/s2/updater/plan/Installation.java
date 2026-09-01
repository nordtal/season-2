package eu.nordtal.s2.updater.plan;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * What is actually lying in one service's volume: the jars in {@code plugins/} and the server jar
 * in {@code .server/}.
 *
 * <h2>Disk is the truth, and nothing else is</h2>
 * The alternative would be a table this module writes after every run. It was not chosen, and the
 * reason is the fault this whole module exists for: a record of what <em>should</em> be installed
 * is exactly what {@code .env} already was on 2026-09-01, when it pinned a release whose
 * {@code smp} jar was 51 273 bytes of scaffold. A second such record would be a second thing that
 * can be right about a server that is wrong.
 *
 * <h2>A missing mount is reported, never created</h2>
 * {@link #scan} on a directory that is not there returns an {@link #absent} installation rather
 * than making one. An updater that quietly reports "nothing installed" for a running SMP server,
 * because its volume was left out of the compose file, would be worse than one that says the mount
 * is missing - the first reads as "all up to date" after the swap.
 */
public record Installation(@NotNull String service,
                           @NotNull Path directory,
                           boolean mounted,
                           @NotNull List<Jar> plugins,
                           @NotNull List<Jar> serverJars) {

    /** Where a service keeps its plugin jars, relative to the volume root. */
    public static final String PLUGINS = "plugins";

    /**
     * Where {@code entrypoint.sh} caches the server jar. A dot directory, so it is invisible to
     * anybody listing the volume - which is deliberate on its side and worth knowing on this one.
     */
    public static final String SERVER_CACHE = ".server";

    /** One jar on disk. */
    public record Jar(@NotNull Path path, @NotNull String fileName) {

        public @Nullable String prefix() {
            return JarName.prefixOf(fileName);
        }

        public @Nullable String version() {
            return JarName.versionOf(fileName);
        }
    }

    public static @NotNull Installation absent(final @NotNull String service, final @NotNull Path directory) {
        return new Installation(service, directory, false, List.of(), List.of());
    }

    /**
     * Reads one service directory. Never writes, never creates, never follows a symlink out of the
     * volume - {@link Files#newDirectoryStream} lists what is there and this only ever asks for
     * regular files.
     */
    public static @NotNull Installation scan(final @NotNull String service, final @NotNull Path directory)
            throws IOException {
        if (!Files.isDirectory(directory)) {
            return absent(service, directory);
        }
        return new Installation(
                service,
                directory,
                true,
                jarsIn(directory.resolve(PLUGINS)),
                jarsIn(directory.resolve(SERVER_CACHE)));
    }

    /**
     * Reads a directory that <b>is</b> the jar's home, rather than a server volume with a
     * {@code plugins/} folder in it.
     * <p>
     * The bot and the updater are each one jar in a volume, not a server: there is no
     * {@code plugins/}, no {@code .server/}, and the container runs whatever jar it finds. The jars
     * land in {@link #plugins()} because that is the list {@link #matching(String)} searches first,
     * and one honest sentence here beats a second field that would be empty for every real server.
     * </p>
     */
    public static @NotNull Installation scanFlat(final @NotNull String service,
                                                 final @NotNull Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return absent(service, directory);
        }
        return new Installation(service, directory, true, jarsIn(directory), List.of());
    }

    /** The installed jar whose prefix matches {@code fileName}'s, or {@code null}. */
    public @Nullable Jar matching(final @NotNull String fileName) {
        final String prefix = JarName.prefixOf(fileName);
        if (prefix == null) {
            return null;
        }
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

    private static @NotNull List<Jar> jarsIn(final @NotNull Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        final List<Jar> jars = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (final Path entry : entries) {
                final String name = entry.getFileName().toString();
                // .partial files are an interrupted download of entrypoint.sh's or of this
                // module's own; they are not jars and must not be read as one.
                if (JarName.isJar(name) && Files.isRegularFile(entry)) {
                    jars.add(new Jar(entry, name));
                }
            }
        }
        jars.sort(Comparator.comparing(Jar::fileName));
        return List.copyOf(jars);
    }
}
