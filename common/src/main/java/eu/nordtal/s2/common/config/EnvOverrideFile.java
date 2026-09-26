package eu.nordtal.s2.common.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Optional;

/**
 * The file beside a jcore config naming which dotted paths the environment currently overrides.
 *
 * A service calls {@link #write} with what {@code ConfigHandle.environmentOverrides()} returned. A missing
 * file means nobody reported, which differs from an empty list. One path per line, since {@code :common}
 * takes no JSON library.
 */
public final class EnvOverrideFile {

    /** The suffix of every such file, so steward-worker does not list them as configurations. */
    public static final String SUFFIX = ".env-overrides.txt";

    private EnvOverrideFile() {}

    /** Returns the file beside {@code configFile}, {@code access.yml} naming {@code access.env-overrides.txt}. */
    public static Path fileFor(final Path configFile) {
        final String name = configFile.getFileName().toString();
        final String base;
        if (name.endsWith(".yml")) {
            base = name.substring(0, name.length() - ".yml".length());
        } else if (name.endsWith(".yaml")) {
            base = name.substring(0, name.length() - ".yaml".length());
        } else {
            base = name;
        }
        return configFile.resolveSibling(base + SUFFIX);
    }

    /**
     * Writes the overridden paths beside {@code configFile} atomically, one per line in the order given.
     *
     * Called with an empty list when nothing is overridden, so the file still says the service reported.
     */
    public static void write(final Path configFile, final List<String> overriddenPaths) throws IOException {
        final Path target = fileFor(configFile);
        final String content = overriddenPaths.isEmpty() ? "" : String.join("\n", overriddenPaths) + "\n";
        final Path tmp = target.resolveSibling(target.getFileName().toString() + ".tmp");
        Files.writeString(
                tmp,
                content,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (final AtomicMoveNotSupportedException notAtomic) {
            // Some filesystems cannot rename atomically; a plain move still beats writing in place.
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Returns the paths the file beside {@code configFile} names, or empty when there is none.
     *
     * @throws IOException if the file exists but cannot be read
     */
    public static Optional<List<String>> read(final Path configFile) throws IOException {
        final Path source = fileFor(configFile);
        if (!Files.isRegularFile(source)) {
            return Optional.empty();
        }
        final List<String> lines = Files.readAllLines(source, StandardCharsets.UTF_8).stream()
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .toList();
        return Optional.of(lines);
    }
}
