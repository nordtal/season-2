package eu.nordtal.s2.stewardagent;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one-shot's own log, kept in a file under the backups, since its container is removed once it exits.
 *
 * Every write to stdout or stderr reaches the file before the call returns, so a kill keeps what came before it.
 */
final class RunLog implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RunLog.class);

    /** The folder under the backups; the archive list and the retention see only that folder's own files. */
    static final String FOLDER = "runs";

    /** How many run logs stay, the new one included. */
    static final int KEPT = 10;

    private static final Pattern NAME = Pattern.compile("(?<id>\\d{1,18})\\.log");

    private final PrintStream out;
    private final PrintStream err;
    private final OutputStream file;

    private RunLog(final PrintStream out, final PrintStream err, final OutputStream file) {
        this.out = out;
        this.err = err;
        this.file = file;
    }

    /**
     * Copies stdout and stderr into {@code <backups>/runs/<id>.log} from now on, and drops the oldest run logs.
     *
     * A file that cannot be written costs the copy and never the run.
     */
    static RunLog keep(final Path backups, final long id) {
        final PrintStream out = System.out;
        final PrintStream err = System.err;
        final Path folder = backups.resolve(FOLDER);
        try {
            Files.createDirectories(folder);
            prune(folder, KEPT - 1);
            final OutputStream file = Files.newOutputStream(
                    folder.resolve(id + ".log"), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            System.setOut(new PrintStream(new Tee(out, file), true, out.charset()));
            System.setErr(new PrintStream(new Tee(err, file), true, err.charset()));
            log.info("This run's log is kept in {}", folder.resolve(id + ".log"));
            return new RunLog(out, err, file);
        } catch (final IOException unwritable) {
            log.warn("This run's log is not kept: {} cannot be written ({})", folder, unwritable.toString());
            return new RunLog(out, err, OutputStream.nullOutputStream());
        }
    }

    /** Deletes the oldest run logs in {@code folder} until at most {@code left} remain; other files stay. */
    static void prune(final Path folder, final int left) throws IOException {
        final List<Path> logs = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(folder)) {
            for (final Path entry : entries) {
                if (Files.isRegularFile(entry)
                        && NAME.matcher(entry.getFileName().toString()).matches()) {
                    logs.add(entry);
                }
            }
        }
        logs.sort(Comparator.comparingLong(RunLog::idOf).reversed());
        for (final Path old : logs.subList(Math.min(left, logs.size()), logs.size())) {
            Files.deleteIfExists(old);
        }
    }

    private static long idOf(final Path runLog) {
        final Matcher name = NAME.matcher(runLog.getFileName().toString());
        return name.matches() ? Long.parseLong(name.group("id")) : 0L;
    }

    /** Puts the streams back and closes the file; a file that will not close costs nothing but a warning. */
    @Override
    public void close() {
        System.out.flush();
        System.err.flush();
        System.setOut(out);
        System.setErr(err);
        try {
            file.close();
        } catch (final IOException unclosed) {
            log.warn("This run's log could not be closed: {}", unclosed.toString());
        }
    }

    /** Writes every byte to the stream it came for and to the file, which is never buffered. */
    private static final class Tee extends OutputStream {

        private final OutputStream console;
        private final OutputStream file;

        Tee(final OutputStream console, final OutputStream file) {
            this.console = console;
            this.file = file;
        }

        @Override
        public void write(final int b) throws IOException {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(final byte[] bytes, final int offset, final int length) throws IOException {
            console.write(bytes, offset, length);
            // stdout and stderr share the file, so one write is never split by the other's.
            synchronized (file) {
                file.write(bytes, offset, length);
            }
        }

        @Override
        public void flush() throws IOException {
            console.flush();
            file.flush();
        }
    }
}
