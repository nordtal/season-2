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
 * An environment file as compose reads it: {@code NAME=value} lines, comments, one layer of quotes.
 *
 * It is never sourced, and no value is ever printed.
 */
final class EnvFile {

    private static final Pattern ASSIGNMENT = Pattern.compile("^\\s*(?:export\\s+)?([A-Za-z_][A-Za-z0-9_]*)\\s*=(.*)$");

    private final Path file;

    EnvFile(final Path file) {
        this.file = file;
    }

    Path path() {
        return file;
    }

    boolean exists() {
        return Files.isRegularFile(file);
    }

    /** @return the value of the last assignment of {@code name}, unquoted; empty when there is none */
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

    /** @return whether {@code name} carries a value somebody gave it: not blank, not {@code REPLACE_ME} */
    boolean isSet(final String name) {
        return value(name)
                .filter(value -> !value.isBlank() && !value.contains("REPLACE_ME"))
                .isPresent();
    }

    /** Writes {@code name=value} over every assignment of it, or appends one; the file stays owner-only. */
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

    /** @return the line numbers, from one, of every uncommented line still holding {@code REPLACE_ME} */
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
            ownerOnly(temporary);
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

    private static void ownerOnly(final Path path) throws IOException {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        } catch (final UnsupportedOperationException e) {
            // Windows has no POSIX modes; the directory's permissions apply.
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
