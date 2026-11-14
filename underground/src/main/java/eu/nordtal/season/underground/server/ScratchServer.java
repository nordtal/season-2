package eu.nordtal.season.underground.server;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** A Minecraft server in a directory of its own, driven through its console and stopped when closed. */
public final class ScratchServer implements AutoCloseable {

    private static final Pattern STARTED = Pattern.compile("Done \\(");
    private static final String END = "\u0000";

    private final Process process;
    private final BufferedWriter console;
    private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
    private final List<String> log = new ArrayList<>();

    private ScratchServer(final Process process) {
        this.process = process;
        console = process.outputWriter(StandardCharsets.UTF_8);
        final Thread reader = new Thread(this::read, "scratch-server-log");
        reader.setDaemon(true);
        reader.start();
    }

    /** Where a scratch server runs, which jar it runs and how. */
    public record Setup(Path directory, Path jar, String java, long seed, int megabytes) {

        /** Returns the folder of the overworld inside the world of this server. */
        public Path overworld() {
            return directory.resolve("world/dimensions/minecraft/overworld");
        }
    }

    /** Writes the files a server needs beside its world: properties, the accepted licence, links to its libraries. */
    public static void prepare(final Setup setup) throws IOException {
        Files.createDirectories(setup.directory());
        for (final String name : new String[] {"libraries", "versions", "cache"}) {
            final Path beside = setup.jar().toAbsolutePath().resolveSibling(name);
            final Path link = setup.directory().resolve(name);
            if (Files.exists(beside) && !Files.exists(link)) {
                Files.createSymbolicLink(link, beside);
            }
        }
        Files.writeString(setup.directory().resolve("eula.txt"), "eula=true\n");
        Files.writeString(
                setup.directory().resolve("server.properties"),
                String.join(
                        "\n",
                        "level-name=world",
                        "level-seed=" + setup.seed(),
                        "online-mode=false",
                        "server-ip=127.0.0.1",
                        "server-port=25701",
                        "max-players=1",
                        "allow-nether=false",
                        "pause-when-empty-seconds=-1",
                        "spawn-protection=0",
                        "view-distance=2",
                        "simulation-distance=2",
                        ""));
    }

    /** Starts the server with further arguments and waits until it has started. */
    public static ScratchServer start(final Setup setup, final String... arguments)
            throws IOException, InterruptedException {
        final List<String> command = new ArrayList<>(List.of(
                setup.java(),
                "-Xms1G",
                "-Xmx" + setup.megabytes() + "M",
                "-jar",
                setup.jar().toAbsolutePath().toString()));
        command.addAll(List.of(arguments));
        command.add("--nogui");
        final Process process = new ProcessBuilder(command)
                .directory(setup.directory().toFile())
                .redirectErrorStream(true)
                .start();
        final ScratchServer server = new ScratchServer(process);
        server.await(STARTED, Duration.ofHours(12));
        return server;
    }

    /** Types a command into the console. */
    public void send(final String command) throws IOException {
        console.write(command);
        console.newLine();
        console.flush();
    }

    /** Types a command and returns the first line of the log after it that matches. */
    public String ask(final String command, final Pattern answer, final Duration patience)
            throws IOException, InterruptedException {
        lines.clear();
        send(command);
        return await(answer, patience);
    }

    /** Returns the next line of the log that matches, and fails when the server ends or the time runs out first. */
    public String await(final Pattern pattern, final Duration patience) throws IOException, InterruptedException {
        final long end = System.nanoTime() + patience.toNanos();
        while (true) {
            final String line = lines.poll(Math.max(1, end - System.nanoTime()), TimeUnit.NANOSECONDS);
            if (line == null || line.equals(END)) {
                throw new IOException((line == null ? "No line like " : "The server ended before a line like ")
                        + pattern + "; the log ends with: " + tail());
            }
            if (pattern.matcher(line).find()) {
                return line;
            }
        }
    }

    /** Returns every line the server has written so far. */
    public List<String> log() {
        synchronized (log) {
            return new ArrayList<>(log);
        }
    }

    @Override
    public void close() throws IOException, InterruptedException {
        if (process.isAlive()) {
            send("stop");
        }
        if (!process.waitFor(10, TimeUnit.MINUTES)) {
            process.destroyForcibly();
        }
    }

    private String tail() {
        final List<String> all = log();
        return String.join(" | ", all.subList(Math.max(0, all.size() - 8), all.size()));
    }

    private void read() {
        try (BufferedReader reader = process.inputReader(StandardCharsets.UTF_8)) {
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                synchronized (log) {
                    log.add(line);
                }
                lines.add(line);
            }
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            lines.add(END);
        }
    }
}
