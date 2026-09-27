package eu.nordtal.s2.steward.worker.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.Test;

/** What the reader does at the end of a frame, the end of a read and the end of the stream. */
class LogFramesTest {

    private static final String LINE = "[04:45:12 INFO]: Zoë fell into lava \u2013 hard luck\n";

    @Test
    void aCharacterSplitAcrossTwoReadsIsOneCharacterNotTwoQuestionMarks() throws IOException {
        final byte[] all = LINE.getBytes(StandardCharsets.UTF_8);
        // Straight through the ë: the first chunk ends on the lead byte of a two-byte character.
        final int cut = indexOfLeadByte(all) + 1;

        assertEquals(
                List.of(LINE.strip()),
                linesOf(false, Arrays.copyOfRange(all, 0, cut), Arrays.copyOfRange(all, cut, all.length)));
    }

    @Test
    void andTheSameAcrossTwoDockerFramesWhichIsWhereItActuallyHappened() throws IOException {
        final byte[] all = LINE.getBytes(StandardCharsets.UTF_8);
        final int cut = indexOfLeadByte(all) + 1;

        assertEquals(
                List.of(LINE.strip()),
                linesOf(true, frame(Arrays.copyOfRange(all, 0, cut)), frame(Arrays.copyOfRange(all, cut, all.length))));
    }

    @Test
    void aCharacterTheStreamEndsHalfwayThroughIsOneReplacementNotALostLine() throws IOException {
        final byte[] all = "done ë".getBytes(StandardCharsets.UTF_8);

        final List<String> lines = linesOf(false, Arrays.copyOfRange(all, 0, all.length - 1));

        assertEquals(1, lines.size(), lines.toString());
        assertEquals(
                "done �",
                lines.getFirst(),
                "the half character is visible as one, and the line it was on is still delivered");
    }

    @Test
    void aFrameHeaderWhosePayloadNeverArrivesIsRefusedNotReadAsZeroes() {
        // The header promises five bytes and the connection closes; ignored, that reads as five NUL characters.
        final byte[] header = {1, 0, 0, 0, 0, 0, 0, 5};

        final EOFException ended = assertThrows(EOFException.class, () -> linesOf(true, header));
        assertEquals(true, ended.getMessage().contains("5-byte payload"), ended.getMessage());
    }

    @Test
    void aFrameCutInHalfIsRefusedTooAndSaysHowFarItGot() {
        final byte[] header = {1, 0, 0, 0, 0, 0, 0, 5};

        final EOFException ended = assertThrows(EOFException.class, () -> linesOf(true, header, new byte[] {'a', 'b'}));
        assertEquals(true, ended.getMessage().contains("2 bytes into"), ended.getMessage());
    }

    @Test
    void oneFrameHoldingThreeLinesIsThreeLinesAndHalfALineWaitsForItsRest() throws IOException {
        assertEquals(
                List.of("one", "two", "three"),
                linesOf(
                        true,
                        frame("one\ntwo\nthr".getBytes(StandardCharsets.UTF_8)),
                        frame("ee\n".getBytes(StandardCharsets.UTF_8))));
    }

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

    private static List<String> linesOf(final boolean multiplexed, final byte[]... chunks) throws IOException {
        final List<String> lines = new ArrayList<>();
        LogFrames.read(delivering(chunks), multiplexed, lines::add);
        return lines;
    }

    /** A stream that hands over exactly these chunks, one per read. */
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
