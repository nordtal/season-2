package eu.nordtal.season.stewardagent.backup;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * Runs programs as one pipeline against one deadline, never through a shell.
 *
 * Each stage's stderr goes to its own temporary file, since merging it would splice warnings into an archive.
 */
final class Pipeline {

    private Pipeline() {}

    /** What a pipeline came to: every stage's status, and whatever any of them said on stderr. */
    record Result(List<Integer> exitCodes, String stderr) {

        boolean failed() {
            return exitCodes.stream().anyMatch(code -> code != 0);
        }

        String describe() {
            final String said = stderr.isBlank() ? "and said nothing" : "saying: " + stderr;
            return "exit " + exitCodes + " " + said;
        }
    }

    /** Runs the stages, writing the last one's output to {@code stdout} or discarding it. */
    static Result run(final Duration wall, final @Nullable Path stdout, final List<List<String>> stages)
            throws IOException, InterruptedException {
        return run(wall, stdout, Map.of(), stages);
    }

    /**
     * Runs the stages with {@code environment} added to every stage's, which keeps a secret off the command line.
     *
     * @return exit {@code -1} when the wall was reached, after the whole pipeline was killed
     */
    static Result run(
            final Duration wall,
            final @Nullable Path stdout,
            final Map<String, String> environment,
            final List<List<String>> stages)
            throws IOException, InterruptedException {
        final List<ProcessBuilder> builders = new ArrayList<>();
        final List<Path> errors = new ArrayList<>();
        for (final List<String> stage : stages) {
            final Path error = Files.createTempFile("pipeline-stderr-", ".log");
            errors.add(error);
            final ProcessBuilder builder = new ProcessBuilder(stage).redirectError(error.toFile());
            builder.environment().putAll(environment);
            builders.add(builder);
        }
        builders.getLast()
                .redirectOutput(
                        stdout == null ? ProcessBuilder.Redirect.DISCARD : ProcessBuilder.Redirect.to(stdout.toFile()));

        List<Process> running = List.of();
        try {
            running = ProcessBuilder.startPipeline(builders);
            final Optional<List<Integer>> codes = awaitAll(running, wall);
            if (codes.isEmpty()) {
                return new Result(
                        List.of(-1),
                        "gave up after " + wall.toMinutes() + " minutes - " + String.join(" ", stages.getFirst())
                                + " did not finish");
            }
            final StringBuilder said = new StringBuilder();
            for (final Path error : errors) {
                final String text =
                        Files.readString(error, StandardCharsets.UTF_8).strip();
                if (!text.isBlank()) {
                    said.append(said.isEmpty() ? "" : "; ").append(text);
                }
            }
            return new Result(codes.get(), said.toString());
        } catch (final InterruptedException interrupted) {
            // A shutdown must not leave a stage writing with nobody waiting on it.
            running.forEach(Process::destroyForcibly);
            throw interrupted;
        } finally {
            for (final Path error : errors) {
                Files.deleteIfExists(error);
            }
        }
    }

    /**
     * Waits for every stage against one deadline, or kills the whole pipeline.
     *
     * @return the exit codes in stage order, or empty if the wall was reached
     */
    static Optional<List<Integer>> awaitAll(final List<Process> running, final Duration wall)
            throws InterruptedException {
        final long deadline = System.nanoTime() + wall.toNanos();
        final List<Integer> codes = new ArrayList<>();
        for (final Process process : running) {
            final long remaining = deadline - System.nanoTime();
            if (remaining <= 0 || !process.waitFor(remaining, TimeUnit.NANOSECONDS)) {
                running.forEach(Process::destroyForcibly);
                return Optional.empty();
            }
            codes.add(process.exitValue());
        }
        return Optional.of(List.copyOf(codes));
    }
}
