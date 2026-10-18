package eu.nordtal.season.dev;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The checkout that this program runs in. The program finds it from the directory where it started. */
final class Repository {

    private static final Pattern VERSION = Pattern.compile("(?m)^version=(.+)$");

    private static final Pattern NAME = Pattern.compile("(?m)^name:\\s*(\\S+)\\s*$");

    private Repository() {}

    static Path root(final Path start) {
        Path at = start.toAbsolutePath();
        while (at != null && !Files.isRegularFile(at.resolve("settings.gradle.kts"))) {
            at = at.getParent();
        }
        if (at == null) {
            throw new Processes.Failure("no settings.gradle.kts above " + start.toAbsolutePath()
                    + " - run this from inside the season-2 checkout");
        }
        return at;
    }

    static String version(final Path root) {
        final Matcher version = VERSION.matcher(read(root.resolve("gradle.properties")));
        if (!version.find()) {
            throw new Processes.Failure("gradle.properties carries no version= line");
        }
        return version.group(1).strip();
    }

    static Optional<String> composeName(final Path root) {
        final Matcher name = NAME.matcher(read(root.resolve("compose.yml")));
        return name.find() ? Optional.of(name.group(1)) : Optional.empty();
    }

    /** Returns the jar that the shadowJar task of a module produces. */
    static Path jar(final Path root, final String module) {
        return root.resolve(module).resolve("build/libs").resolve(module + "-" + version(root) + ".jar");
    }

    private static String read(final Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }
}
