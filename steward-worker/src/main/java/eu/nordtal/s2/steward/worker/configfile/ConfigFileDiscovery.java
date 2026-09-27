package eu.nordtal.s2.steward.worker.configfile;

import eu.nordtal.s2.common.config.EnvOverrideFile;
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

    /**
     * A directory whose contents are scratch, not configuration.
     *
     * {@code spark/tmp} holds profiler dumps and an {@code about.txt}. Matched as a whole path segment, so a file
     * honestly called {@code tmp.yml} is still listed.
     */
    private static final String SCRATCH_DIRECTORY = "tmp";

    /** How many bytes of a file {@link #isProbablyText} looks at before deciding. */
    private static final int SNIFF_LENGTH = 8000;

    private ConfigFileDiscovery() {}

    /**
     * Every config file under the mount, one directory per service.
     *
     * {@code /configs/steward-worker/steward.yml} is service {@code steward-worker}, name {@code steward.yml};
     * {@code /configs/smp/nordtal-smp/config.yml} is service {@code smp}, name {@code nordtal-smp/config.yml}. A
     * file lying directly in the root has no service directory above it and is reported with an empty service
     * rather than dropped.
     *
     * This filters by content, not by name or extension, the way {@code git} and {@code grep} decide a file is
     * worth treating as text: {@link #isProbablyText} sniffs the first few kilobytes for a NUL byte. A file this
     * process cannot read is not sniffed and not excluded either: hiding a config nobody can open is a worse answer
     * than showing it and letting {@link ConfigLocation#readable()} say why it is dead.
     *
     * Three things are excluded by name rather than by content. Every {@code <name>.schema.json} jcore writes
     * beside a config file describes another file in this listing, never a file of its own. Every {@code *.bak} is
     * jcore's copy of a file as it was before the last write. And anything under a {@code tmp} directory is
     * scratch. An {@code <name>.env-overrides.txt} marker, and its {@code .tmp} while a write is landing it, are
     * excluded the same way: editing one would change what the warning says without changing what the environment
     * actually overrides. Anything under a {@code messages} directory is left out too: those are the saved
     * translations {@link MessageBundles} already shows as a bundle, not configuration.
     *
     * @param root the mount point
     * @return every text file beneath it, by service then name. Empty if the root does not exist: an unmounted
     *     volume is a normal state to report, not a failure
     * @throws UncheckedIOException if the root exists but cannot be walked
     */
    static List<ConfigLocation> discover(final Path root) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    // A link is not a config file: `isRegularFile` follows one, and this list is matched by string.
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

    /**
     * Whether no directory between {@code root} and {@code path} is scratch, or holds saved translations.
     *
     * Compared segment by segment rather than with {@code contains}, so {@code smp/tmp/about.txt} is excluded and
     * {@code smp/tmpl/config.yml} is not - and relative to the root, so a test fixture under {@code /tmp} is not
     * mistaken for a scratch directory of its own.
     */
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
     * Whether a file looks like text: a NUL byte in the first few kilobytes means binary.
     *
     * A file this process cannot read is treated as text rather than excluded: hiding a config nobody can open yet
     * is a worse answer than listing it dead. An empty file is text.
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
            // Unreadable in a way `Files.isReadable` did not catch: same answer as above, same reason.
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
        // The move needs the directory too: a writable file in a read-only mount still cannot be saved.
        final Path directory = file.toAbsolutePath().getParent();
        final boolean writable = Files.isWritable(file) && directory != null && Files.isWritable(directory);
        return new ConfigLocation(service, name.toString(), file, Files.isReadable(file), writable);
    }
}
