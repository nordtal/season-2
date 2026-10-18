package eu.nordtal.season.dev;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An environment file as compose reads it: {@code NAME=value} lines, comments and one layer of quotes.
 *
 * The class never sources the file and never prints a value.
 */
final class EnvFile {

    private static final Pattern ASSIGNMENT = Pattern.compile("^\\s*(?:export\\s+)?([A-Za-z_][A-Za-z0-9_]*)\\s*=(.*)$");

    private static final String OWNER_ONLY = "rw-------";

    /** A container runs as its own uid, so everyone can read the file of a service. */
    private static final String READABLE = "rw-r--r--";

    private final Path file;

    private final String mode;

    EnvFile(final Path file) {
        this(file, OWNER_ONLY);
    }

    private EnvFile(final Path file, final String mode) {
        this.file = file;
        this.mode = mode;
    }

    static EnvFile readableByItsService(final Path file) {
        return new EnvFile(file, READABLE);
    }

    Path path() {
        return file;
    }

    boolean exists() {
        return Files.isRegularFile(file);
    }

    Optional<String> value(final String name) {
        String found = null;
        for (final String line : lines()) {
            final Matcher assignment = ASSIGNMENT.matcher(line);
            if (assignment.matches() && assignment.group(1).equals(name)) {
                found = unquote(assignment.group(2));
            }
        }
        return Optional.ofNullable(found);
    }

    boolean isSet(final String name) {
        return value(name)
                .filter(value -> !value.isBlank() && !value.contains("REPLACE_ME"))
                .isPresent();
    }

    void set(final String name, final String value) {
        final List<String> out = new ArrayList<>();
        boolean replaced = false;
        for (final String line : lines()) {
            final Matcher assignment = ASSIGNMENT.matcher(line);
            if (assignment.matches() && assignment.group(1).equals(name)) {
                out.add(name + "=" + value);
                replaced = true;
            } else {
                out.add(line);
            }
        }
        if (!replaced) {
            out.add(name + "=" + value);
        }
        write(out);
    }

    List<Integer> replaceMeLines() {
        final List<Integer> numbers = new ArrayList<>();
        final List<String> lines = lines();
        for (int index = 0; index < lines.size(); index++) {
            final String line = lines.get(index);
            if (line.contains("REPLACE_ME") && !line.strip().startsWith("#")) {
                numbers.add(index + 1);
            }
        }
        return numbers;
    }

    private List<String> lines() {
        if (!exists()) {
            return List.of();
        }
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }

    private void write(final List<String> lines) {
        try {
            final Path directory = file.toAbsolutePath().getParent();
            final Path temporary = Files.createTempFile(directory, ".env", ".tmp");
            permit(temporary, mode);
            Files.write(temporary, lines, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (final AtomicMoveNotSupportedException e) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot write " + file, e);
        }
    }

    private static void permit(final Path path, final String mode) throws IOException {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(mode));
        } catch (final UnsupportedOperationException e) {
            // Windows has no POSIX modes, so the permissions of the directory apply.
        }
    }

    private static String unquote(final String raw) {
        final String value = raw.strip();
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
