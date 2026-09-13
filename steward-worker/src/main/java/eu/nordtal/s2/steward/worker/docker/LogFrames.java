package eu.nordtal.s2.steward.worker.docker;

import org.jetbrains.annotations.NotNull;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * Docker's log stream comes in two shapes, and reading the wrong one is visible to everybody.
 *
 * <h2>The two shapes</h2>
 * A container created <b>without</b> a TTY multiplexes stdout and stderr over one connection, so
 * every payload is preceded by an eight-byte header: one byte of stream number, three of padding,
 * then the length as a big-endian {@code uint32}. A container created <b>with</b> a TTY has no
 * second stream to separate, so the bytes are the output and nothing else.
 *
 * <p>Which one arrives is not a guess: {@code Config.Tty} on the container says so, and the
 * response's content type says so again ({@code application/vnd.docker.multiplexed-stream}).
 * Getting it wrong does not fail - it puts a smear of control characters and a chopped first word
 * at the head of every line in the interface, which is the sort of thing that gets shipped.</p>
 *
 * <h2>Why the frames are re-split on newlines</h2>
 * A frame is not a line. Docker flushes when it flushes, so one frame can hold half a line, three
 * lines, or a line with no terminator yet. The interface wants lines, so the remainder of a frame
 * that does not end in a newline is held back until the rest of it turns up.
 */
public final class LogFrames {

    /** Docker's stream numbers, as they appear in the first header byte. */
    public static final int STDIN = 0;
    public static final int STDOUT = 1;
    public static final int STDERR = 2;

    private LogFrames() {
    }

    /**
     * Reads until the stream ends or the thread is interrupted, handing over whole lines.
     *
     * @param multiplexed whether the eight-byte header is there - {@code Config.Tty} is false
     * @param line        called for each complete line, without its terminator
     */
    public static void read(final @NotNull InputStream in, final boolean multiplexed,
                            final @NotNull Consumer<String> line) throws IOException {
        final StringBuilder held = new StringBuilder();
        if (multiplexed) {
            final byte[] header = new byte[8];
            while (!Thread.currentThread().isInterrupted()) {
                if (!readFully(in, header, header.length)) {
                    break;
                }
                final long length = ((long) (header[4] & 0xff) << 24)
                        | ((header[5] & 0xff) << 16)
                        | ((header[6] & 0xff) << 8)
                        | (header[7] & 0xff);
                if (length > Integer.MAX_VALUE) {
                    throw new IOException("a log frame claiming " + length + " bytes");
                }
                final byte[] payload = new byte[(int) length];
                readFully(in, payload, payload.length);
                split(held, new String(payload, StandardCharsets.UTF_8), line);
            }
        } else {
            final byte[] buffer = new byte[8 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1 && !Thread.currentThread().isInterrupted()) {
                split(held, new String(buffer, 0, read, StandardCharsets.UTF_8), line);
            }
        }
        // Whatever is left had no newline after it. It is still output somebody wrote, and dropping
        // it would silently lose the last line of every container that ends without one.
        if (!held.isEmpty()) {
            line.accept(held.toString());
        }
    }

    private static void split(final StringBuilder held, final String text,
                              final Consumer<String> line) {
        held.append(text);
        int start = 0;
        for (int i = 0; i < held.length(); i++) {
            if (held.charAt(i) == '\n') {
                final int end = i > start && held.charAt(i - 1) == '\r' ? i - 1 : i;
                line.accept(held.substring(start, end));
                start = i + 1;
            }
        }
        held.delete(0, start);
    }

    /**
     * Fills the buffer, or says the stream ended.
     *
     * <p>The two endings are not the same and must not be conflated: nothing read at all is the
     * stream closing between frames, which is ordinary and how a follow ends. Something read and
     * then nothing is a frame cut in half, which means the next eight bytes this reader takes for a
     * header are somebody's log text - so it throws rather than carrying on and producing garbage.
     * </p>
     */
    private static boolean readFully(final InputStream in, final byte[] buffer, final int length)
            throws IOException {
        int filled = 0;
        while (filled < length) {
            final int read = in.read(buffer, filled, length - filled);
            if (read == -1) {
                if (filled == 0) {
                    return false;
                }
                throw new EOFException("the log stream ended " + filled + " bytes into a "
                        + length + "-byte read");
            }
            filled += read;
        }
        return true;
    }
}
