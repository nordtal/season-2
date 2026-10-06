package eu.nordtal.season.stewardagent.descriptor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/** What was read out of each jar, read again only when its size or modification time changes. */
final class StampCache<T> {

    /** Reads one jar; {@code null} when it carries nothing to read. */
    @FunctionalInterface
    interface JarReader<T> {
        @Nullable
        T read(Path jar);
    }

    private final Map<String, Read<T>> jars = new ConcurrentHashMap<>();
    private final JarReader<T> reader;

    StampCache(final JarReader<T> reader) {
        this.reader = reader;
    }

    /** What the jar holds, from the last reading while it is unchanged; {@code null} for a file that cannot be read. */
    @Nullable
    T of(final Path jar) {
        final String stamp;
        try {
            final BasicFileAttributes attributes = Files.readAttributes(jar, BasicFileAttributes.class);
            stamp = attributes.size() + "@" + attributes.lastModifiedTime().toMillis();
        } catch (final IOException e) {
            return null;
        }
        final Read<T> known = jars.get(jar.toString());
        if (known != null && known.stamp().equals(stamp)) {
            return known.value();
        }
        final T value = reader.read(jar);
        jars.put(jar.toString(), new Read<>(stamp, value));
        return value;
    }

    private record Read<T>(String stamp, @Nullable T value) {}
}
