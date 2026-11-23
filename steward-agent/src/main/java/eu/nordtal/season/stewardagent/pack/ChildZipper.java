package eu.nordtal.season.stewardagent.pack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs the pack builder in a child JVM at the lowest CPU priority, so a build never slows the game servers.
 *
 * The child runs from this process's own classpath, which holds the pack builder.
 */
final class ChildZipper implements PackZipper, MapZipper {

    private static final String MAIN = "eu.nordtal.season.packbuilder.PackBuilderMain";
    /** The pack is under a megabyte, and the map draws one tile at a time, so the child needs little heap. */
    private static final String HEAP = "-Xmx512m";
    /** The end of the output kept for the reason of a failed build. */
    private static final int TAIL = 2000;

    private final Duration timeout;

    ChildZipper(final Duration timeout) {
        this.timeout = timeout;
    }

    @Override
    public void zip(final Path facts, final Path prizes, final Path texts, final Path zip, final List<Path> roots)
            throws IOException {
        final List<String> arguments =
                new ArrayList<>(List.of("zip", facts.toString(), prizes.toString(), texts.toString(), zip.toString()));
        roots.forEach(root -> arguments.add(root.toString()));
        run(arguments, zip);
    }

    @Override
    public void map(final Path tiles, final Path zip) throws IOException {
        run(List.of("map", tiles.toString(), zip.toString()), zip);
    }

    /** Runs the pack builder with {@code arguments}, which write {@code zip}. */
    private void run(final List<String> arguments, final Path zip) throws IOException {
        final List<String> command = new ArrayList<>(List.of(
                "nice",
                "-n",
                "19",
                ProcessHandle.current().info().command().orElse("java"),
                HEAP,
                "-cp",
                System.getProperty("java.class.path"),
                MAIN));
        command.addAll(arguments);
        final Path log = zip.resolveSibling(zip.getFileName() + ".log");
        try {
            final Process child = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(log.toFile())
                    .start();
            try {
                if (!child.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    child.destroyForcibly();
                    throw new IOException("the pack builder did not finish within " + timeout);
                }
            } catch (final InterruptedException interrupted) {
                child.destroyForcibly();
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while the pack builder ran", interrupted);
            }
            if (child.exitValue() != 0) {
                throw new IOException("the pack builder exited with " + child.exitValue() + ": "
                        + tail(log).strip());
            }
        } finally {
            Files.deleteIfExists(log);
        }
    }

    /** The end of the child's output, the reason of a failed build. */
    private static String tail(final Path log) throws IOException {
        final String text = Files.readString(log, StandardCharsets.UTF_8);
        return text.length() > TAIL ? text.substring(text.length() - TAIL) : text;
    }
}
