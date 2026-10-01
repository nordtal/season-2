package eu.nordtal.s2.steward.configfile;

import eu.nordtal.s2.settings.EnvOverrideFile;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** Finds every config file under the shared mount, one directory per service. */
final class ConfigFileDiscovery {

    /** The suffix {@link eu.nordtal.jcore.config.schema.SchemaWriter#schemaFileFor} always writes. */
    private static final String SCHEMA_SUFFIX = ".schema.json";

    /** jcore's own copy of a file as it was before the last write: it parses but nothing reads it back. */
    private static final String BACKUP_SUFFIX = ".bak";

    /** A scratch directory, matched as a whole path segment. */
    private static final String SCRATCH_DIRECTORY = "tmp";

    /** How many bytes of a file {@link #isProbablyText} looks at before deciding. */
    private static final int SNIFF_LENGTH = 8000;

    private ConfigFileDiscovery() {}

    /**
     * Returns every text config file under the mount, by service then name.
     *
     * @param root the mount point
     * @return every text file beneath it; empty if the root does not exist
     * @throws UncheckedIOException if the root exists but cannot be walked
     */
    static List<ConfigLocation> discover(final Path root) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    // A link is not a config file; `isRegularFile` follows one, and this list is matched by string.
                    .filter(path -> !Files.isSymbolicLink(path))
                    .filter(path -> !path.getFileName().toString().endsWith(SCHEMA_SUFFIX))
                    .filter(path -> !path.getFileName().toString().endsWith(BACKUP_SUFFIX))
                    .filter(path -> !path.getFileName().toString().endsWith(EnvOverrideFile.SUFFIX))
                    .filter(path -> !path.getFileName().toString().endsWith(EnvOverrideFile.SUFFIX + ".tmp"))
                    .filter(path -> isUnderNoScratchDirectory(root, path))
                    .filter(ConfigFileDiscovery::isProbablyText)
                    .map(path -> locationOf(root, path))
                    .sorted(Comparator.comparing(ConfigLocation::service).thenComparing(ConfigLocation::name))
                    .toList();
        } catch (final IOException e) {
            throw new UncheckedIOException("Cannot list the config files under " + root, e);
        }
    }

    /** Returns whether no directory between {@code root} and {@code path} is scratch or holds saved translations. */
    private static boolean isUnderNoScratchDirectory(final Path root, final Path path) {
        for (final Path segment : root.relativize(path)) {
            if (segment.toString().equals(SCRATCH_DIRECTORY)
                    || segment.toString().equals(MessageBundles.DIRECTORY)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns whether a file looks like text: no NUL byte in the first few kilobytes.
     *
     * An unreadable file counts as text, so it is listed as dead rather than hidden.
     */
    private static boolean isProbablyText(final Path path) {
        if (!Files.isReadable(path)) {
            return true;
        }
        try (var in = Files.newInputStream(path)) {
            final byte[] buffer = new byte[SNIFF_LENGTH];
            final int read = in.read(buffer);
            for (int i = 0; i < read; i++) {
                if (buffer[i] == 0) {
                    return false;
                }
            }
            return true;
        } catch (final IOException e) {
            // Unreadable in a way `Files.isReadable` did not catch.
            return true;
        }
    }

    private static ConfigLocation locationOf(final Path root, final Path file) {
        final Path relative = root.relativize(file);
        final String service = relative.getNameCount() > 1 ? relative.getName(0).toString() : "";
        final Path rest = relative.getNameCount() > 1 ? relative.subpath(1, relative.getNameCount()) : relative;
        final StringBuilder name = new StringBuilder();
        for (final Path segment : rest) {
            if (!name.isEmpty()) {
                name.append('/');
            }
            name.append(segment);
        }
        // A writable file in a read-only mount still cannot be saved.
        final Path directory = file.toAbsolutePath().getParent();
        final boolean writable = Files.isWritable(file) && directory != null && Files.isWritable(directory);
        return new ConfigLocation(service, name.toString(), file, Files.isReadable(file), writable);
    }
}
