package eu.nordtal.season.stewardagent.pack;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
final class ChildZipper implements PackZipper {

    private static final String MAIN = "eu.nordtal.season.packbuilder.PackBuilderMain";
    /** The pack is under a megabyte, so the child needs little heap. */
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
        final List<String> command = new ArrayList<>(List.of(
                "nice",
                "-n",
                "19",
                ProcessHandle.current().info().command().orElse("java"),
                HEAP,
                "-cp",
                System.getProperty("java.class.path"),
                MAIN,
                "zip",
                facts.toString(),
                prizes.toString(),
                texts.toString(),
                zip.toString()));
        roots.forEach(root -> command.add(root.toString()));
        final Process child =
                new ProcessBuilder(command).redirectErrorStream(true).start();
        final StringBuilder output = new StringBuilder();
        final Thread reader = Thread.ofVirtual().start(() -> drain(child.getInputStream(), output));
        try {
            if (!child.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                child.destroyForcibly();
                throw new IOException("the pack builder did not finish within " + timeout);
            }
            reader.join(Duration.ofSeconds(5));
        } catch (final InterruptedException interrupted) {
            child.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while the pack builder ran", interrupted);
        }
        if (child.exitValue() != 0) {
            final String text;
            synchronized (output) {
                text = output.length() > TAIL ? output.substring(output.length() - TAIL) : output.toString();
            }
            throw new IOException("the pack builder exited with " + child.exitValue() + ": " + text.strip());
        }
    }

    private static void drain(final InputStream in, final StringBuilder output) {
        try (in) {
            final byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                synchronized (output) {
                    output.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
                }
            }
        } catch (final IOException ignored) {
            // The child ended; what it wrote so far is the reason.
        }
    }
}
