package eu.nordtal.season.dev;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Runs the programs that this program drives: the Gradle wrapper and Docker. */
final class Processes {

    private final Path root;
    private final boolean windows;

    Processes(final Path root) {
        this.root = root;
        this.windows =
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
    }

    void gradle(final String... tasks) {
        final List<String> command = new ArrayList<>(
                windows ? List.of("cmd", "/c", root.resolve("gradlew.bat").toString()) : List.of("sh", "gradlew"));
        command.addAll(List.of(tasks));
        require(run(command), "gradlew " + String.join(" ", tasks));
    }

    int run(final List<String> command) {
        try {
            return start(command, true).waitFor();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Failure(String.join(" ", command) + " was interrupted");
        }
    }

    String output(final List<String> command) {
        try {
            final Process process = new ProcessBuilder(command)
                    .directory(root.toFile())
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .start();
            final String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            process.waitFor();
            return out.strip();
        } catch (final IOException e) {
            throw new Failure("cannot run " + command.getFirst() + ": " + e.getMessage());
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Failure(String.join(" ", command) + " was interrupted");
        }
    }

    Process start(final List<String> command, final boolean withInput) {
        try {
            final ProcessBuilder builder = new ProcessBuilder(command)
                    .directory(root.toFile())
                    .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                    .redirectError(ProcessBuilder.Redirect.INHERIT);
            if (withInput) {
                builder.redirectInput(ProcessBuilder.Redirect.INHERIT);
            }
            return builder.start();
        } catch (final IOException e) {
            throw new Failure("cannot run " + command.getFirst() + ": " + e.getMessage()
                    + (command.getFirst().equals("docker") ? ". Is Docker installed and on the PATH?" : ""));
        }
    }

    static void require(final int status, final String what) {
        if (status != 0) {
            throw new Failure(what + " failed (exit " + status + ")");
        }
    }

    static final class Failure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Failure(final String message) {
            super(message);
        }
    }
}
