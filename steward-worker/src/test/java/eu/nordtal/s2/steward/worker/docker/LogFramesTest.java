package eu.nordtal.s2.steward.worker.docker;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * What the reader does at the boundaries nobody chooses: the end of a frame, the end of a read, and
 * the end of the stream.
 *
 * <p>None of it needs a daemon. A docker log stream is bytes in an order, and the two shapes are
 * described exactly enough in {@link LogFrames} to be written out here.</p>
 */
class LogFramesTest {

    private static final String LINE = "[04:45:12 INFO]: Zoë fell into lava – hard luck\n";

    @Test
    @DisplayName("a character split across two reads is one character, not two question marks")
    void aCharacterSurvivesTheChunkBoundary() throws IOException {
        final byte[] all = LINE.getBytes(StandardCharsets.UTF_8);
        // Straight through the ë: the first chunk ends on the lead byte of a two-byte character.
        final int cut = indexOfLeadByte(all) + 1;

        assertEquals(List.of(LINE.strip()),
                linesOf(false, Arrays.copyOfRange(all, 0, cut), Arrays.copyOfRange(all, cut, all.length)));
    }

    @Test
    @DisplayName("and the same across two docker frames, which is where it actually happened")
    void aCharacterSurvivesTheFrameBoundary() throws IOException {
        final byte[] all = LINE.getBytes(StandardCharsets.UTF_8);
        final int cut = indexOfLeadByte(all) + 1;

        assertEquals(List.of(LINE.strip()),
                linesOf(true, frame(Arrays.copyOfRange(all, 0, cut)),
                        frame(Arrays.copyOfRange(all, cut, all.length))));
    }

    @Test
    @DisplayName("a character the stream ends halfway through is one replacement, not a lost line")
    void anUnfinishableCharacterIsStillALine() throws IOException {
        final byte[] all = "done ë".getBytes(StandardCharsets.UTF_8);

        final List<String> lines = linesOf(false, Arrays.copyOfRange(all, 0, all.length - 1));

        assertEquals(1, lines.size(), lines.toString());
        assertEquals("done �", lines.getFirst(),
                "the half character is visible as one, and the line it was on is still delivered");
    }

    @Test
    @DisplayName("a frame header whose payload never arrives is refused, not read as zeroes")
    void aPayloadThatNeverArrivesIsAnError() {
        // The header promises five bytes and the connection closes. Ignored, the reader handed the
        // interface five NUL characters as a log line - and then took the next bytes of a dead
        // stream for the following header.
        final byte[] header = {1, 0, 0, 0, 0, 0, 0, 5};

        final EOFException ended = assertThrows(EOFException.class, () -> linesOf(true, header));
        assertEquals(true, ended.getMessage().contains("5-byte payload"), ended.getMessage());
    }

    @Test
    @DisplayName("a frame cut in half is refused too, and says how far it got")
    void aHalfPayloadIsAnError() {
        final byte[] header = {1, 0, 0, 0, 0, 0, 0, 5};

        final EOFException ended = assertThrows(EOFException.class,
                () -> linesOf(true, header, new byte[]{'a', 'b'}));
        assertEquals(true, ended.getMessage().contains("2 bytes into"), ended.getMessage());
    }

    @Test
    @DisplayName("one frame holding three lines is three lines, and half a line waits for its rest")
    void framesAreNotLines() throws IOException {
        assertEquals(List.of("one", "two", "three"),
                linesOf(true, frame("one\ntwo\nthr".getBytes(StandardCharsets.UTF_8)),
                        frame("ee\n".getBytes(StandardCharsets.UTF_8))));
    }

    // -----------------------------------------------------------------------------------------

    private static int indexOfLeadByte(final byte[] bytes) {
        for (int i = 0; i < bytes.length; i++) {
            if ((bytes[i] & 0xff) == 0xC3) {
                return i;
            }
        }
        throw new AssertionError("no two-byte character in the fixture");
    }

    /** Docker's eight-byte header for one stdout payload, then the payload. */
    private static byte[] frame(final byte[] payload) {
        final byte[] framed = new byte[8 + payload.length];
        framed[0] = LogFrames.STDOUT;
        framed[4] = (byte) (payload.length >>> 24);
        framed[5] = (byte) (payload.length >>> 16);
        framed[6] = (byte) (payload.length >>> 8);
        framed[7] = (byte) payload.length;
        System.arraycopy(payload, 0, framed, 8, payload.length);
        return framed;
    }

    private static List<String> linesOf(final boolean multiplexed, final byte[]... chunks)
            throws IOException {
        final List<String> lines = new ArrayList<>();
        LogFrames.read(delivering(chunks), multiplexed, lines::add);
        return lines;
    }

    /**
     * A stream that hands over exactly these chunks, one per read.
     *
     * <p>That is the whole point: {@code InputStream.read(byte[])} is allowed to return any number
     * of bytes, and everything here is about what happens at the seam between two of them.</p>
     */
    private static InputStream delivering(final byte[]... chunks) {
        final Deque<byte[]> queue = new ArrayDeque<>(List.of(chunks));
        return new InputStream() {

            private byte[] current = new byte[0];
            private int at;

            @Override
            public int read() {
                final byte[] one = new byte[1];
                return read(one, 0, 1) == -1 ? -1 : one[0] & 0xff;
            }

            @Override
            public int read(final byte[] into, final int off, final int len) {
                while (at >= current.length) {
                    if (queue.isEmpty()) {
                        return -1;
                    }
                    current = queue.poll();
                    at = 0;
                }
                final int taken = Math.min(len, current.length - at);
                System.arraycopy(current, at, into, off, taken);
                at += taken;
                return taken;
            }
        };
    }
}
