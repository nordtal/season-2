package eu.nordtal.season.stewardagent.docker;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * Reads Docker's log stream as lines, with or without the eight-byte multiplexing header per frame.
 *
 * {@code Config.Tty} says which shape it is. Frames are not lines, so a partial line waits for the rest.
 */
public final class LogFrames {

    /** Docker's stream numbers, as they appear in the first header byte. */
    public static final int STDIN = 0;

    public static final int STDOUT = 1;
    public static final int STDERR = 2;

    private LogFrames() {}

    /**
     * Reads until the stream ends or the thread is interrupted, handing over whole lines.
     *
     * @param multiplexed whether the eight-byte header is there, which is when {@code Config.Tty} is false
     * @param line called for each complete line, without its terminator
     */
    public static void read(final InputStream in, final boolean multiplexed, final Consumer<String> line)
            throws IOException {
        final StringBuilder held = new StringBuilder();
        final Utf8 text = new Utf8();
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
                if (!readFully(in, payload, payload.length)) {
                    // Ignoring this would hand the interface `length` NUL characters as a log line.
                    throw new EOFException("the log stream ended before a " + length + "-byte payload");
                }
                split(held, text.decode(payload, payload.length), line);
            }
        } else {
            final byte[] buffer = new byte[8 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1 && !Thread.currentThread().isInterrupted()) {
                split(held, text.decode(buffer, read), line);
            }
        }
        // A character cut in half by the end of the stream becomes one replacement character.
        split(held, text.rest(), line);
        // The last line may lack a newline and is kept.
        if (!held.isEmpty()) {
            line.accept(held.toString());
        }
    }

    private static void split(final StringBuilder held, final String text, final Consumer<String> line) {
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
     * UTF-8, decoded across chunk boundaries so a character split between two chunks survives.
     *
     * Malformed input is replaced rather than thrown: a log line is not worth ending a follow over.
     */
    private static final class Utf8 {

        private final CharsetDecoder decoder = StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);

        private byte[] carry = new byte[0];

        /** The characters this chunk completes; its trailing half-character waits for the next. */
        String decode(final byte[] bytes, final int length) {
            final ByteBuffer in = ByteBuffer.allocate(carry.length + length);
            in.put(carry).put(bytes, 0, length).flip();
            final CharBuffer out = CharBuffer.allocate(in.remaining() + 1);
            decoder.decode(in, out, false);
            carry = new byte[in.remaining()];
            in.get(carry);
            return out.flip().toString();
        }

        /** What is left when the stream ends: an unfinished character, as one U+FFFD. */
        String rest() {
            final ByteBuffer in = ByteBuffer.wrap(carry);
            final CharBuffer out = CharBuffer.allocate(carry.length + 1);
            decoder.decode(in, out, true);
            decoder.flush(out);
            carry = new byte[0];
            return out.flip().toString();
        }
    }

    /** Fills the buffer, or says the stream ended between frames; a frame cut in half throws instead. */
    private static boolean readFully(final InputStream in, final byte[] buffer, final int length) throws IOException {
        int filled = 0;
        while (filled < length) {
            final int read = in.read(buffer, filled, length - filled);
            if (read == -1) {
                if (filled == 0) {
                    return false;
                }
                throw new EOFException("the log stream ended " + filled + " bytes into a " + length + "-byte read");
            }
            filled += read;
        }
        return true;
    }
}
