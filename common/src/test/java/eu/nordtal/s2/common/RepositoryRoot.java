package eu.nordtal.s2.common;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Locates the repository root for tests that read files outside their own source set.
 *
 * Every file reached here must also be declared through {@code repositoryRootTestInputs}, or the test stays up to date.
 */
public final class RepositoryRoot {

    private RepositoryRoot() {}

    /** Returns the directory holding {@code settings.gradle.kts}. */
    public static Path path() {
        Path at = Path.of("").toAbsolutePath();
        while (at != null && !Files.isRegularFile(at.resolve("settings.gradle.kts"))) {
            at = at.getParent();
        }
        if (at == null) {
            throw new IllegalStateException(
                    "no settings.gradle.kts above " + Path.of("").toAbsolutePath());
        }
        return at;
    }

    /** Returns {@code relative}, resolved against the repository root. */
    public static Path resolve(final String relative) {
        return path().resolve(relative);
    }

    /**
     * Returns {@code path} relative to the repository root, separated by {@code /} on every platform.
     *
     * @param path an absolute path inside the repository
     */
    public static String relative(final Path path) {
        return relative(path(), path);
    }

    /**
     * Returns {@code path} relative to {@code base}, separated by {@code /} on every platform.
     *
     * @param path an absolute path inside {@code base}
     */
    public static String relative(final Path base, final Path path) {
        final StringBuilder name = new StringBuilder();
        for (final Path segment : base.relativize(path)) {
            if (name.length() > 0) {
                name.append('/');
            }
            name.append(segment);
        }
        return name.toString();
    }

    /** Returns the UTF-8 contents of {@code relative}, resolved against the repository root. */
    public static String read(final String relative) {
        try {
            return Files.readString(resolve(relative), StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot read " + relative, e);
        }
    }
}
