package eu.nordtal.s2.common;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Locates the repository root for tests that read files outside their own source set.
 *
 * It anchors on the directory holding {@code settings.gradle.kts}, which no file can shadow. Every file
 * reached here must also be declared through {@code repositoryRootTestInputs}, or the test stays up to date.
 */
public final class RepositoryRoot {

    private RepositoryRoot() {}

    /** @return the directory holding {@code settings.gradle.kts} */
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

    /** @return {@code relative}, resolved against the repository root */
    public static Path resolve(final String relative) {
        return path().resolve(relative);
    }

    /**
     * Names a file the way this repository names it: repository-relative, separated by {@code /}.
     *
     * Every test here that walks a source tree compares the path it finds against an allowlist
     * whose keys are written the way a person writes a path - {@code smp/src/main/java/...}. On
     * Windows {@code Path.toString()} answers with backslashes, so the comparison silently fails to
     * match and the allowlisted file is reported as a violation of the very rule it is exempt from.
     * A test that accuses the file it was told to permit is worse than no test, because the honest
     * reading of its output is that the rule was broken.
     *
     * One implementation rather than seven copies of
     * {@code RepositoryRoot.path().relativize(x).toString()}, which is how the bug came to exist in
     * more than one place at once.
     *
     * @param path an absolute path inside the repository
     * @return e.g. {@code smp/src/main/java/eu/nordtal/s2/smp/SmpPlugin.java}, on every platform
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

    /** @return the UTF-8 contents of {@code relative}, resolved against the repository root */
    public static String read(final String relative) {
        try {
            return Files.readString(resolve(relative), StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot read " + relative, e);
        }
    }
}
