package eu.nordtal.season.limboprotocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Round-trips the {@code nordtal:limbo} wire format, where a message that does not parse is silently lost.
 *
 * {@link LimboProtocol#decode(byte[])} must never throw, so the malformed cases are ordinary input.
 */
class LimboProtocolTest {

    @Test
    void everyWaitReasonRoundTrips() {
        for (final WaitReason reason : WaitReason.values()) {
            final Optional<LimboProtocol.Message> decoded = LimboProtocol.decode(LimboProtocol.wait(reason));

            assertTrue(decoded.isPresent(), reason + " did not decode");
            assertEquals(LimboProtocol.Type.WAIT, decoded.get().type());
            assertEquals(reason, decoded.get().reason());
        }
    }

    @Test
    void readyRoundTripsAndCarriesNoReason() {
        final LimboProtocol.Message message =
                LimboProtocol.decode(LimboProtocol.ready()).orElseThrow();

        assertEquals(LimboProtocol.Type.READY, message.type());
        assertEquals(null, message.reason());
    }

    @Test
    void aWaitIsTwoBytesOfHeaderAndReadyIsNothingElse() {
        // The header is the compatibility surface between proxy and backend versions.
        assertEquals(LimboProtocol.VERSION, LimboProtocol.ready()[0]);
        assertEquals(2, LimboProtocol.ready().length);
        assertEquals(LimboProtocol.VERSION, LimboProtocol.wait(WaitReason.PACK)[0]);
    }

    @Test
    void nullAndTruncatedPayloadsDecodeToNothing() {
        assertEquals(Optional.empty(), LimboProtocol.decode(null));
        assertEquals(Optional.empty(), LimboProtocol.decode(new byte[0]));
        assertEquals(Optional.empty(), LimboProtocol.decode(new byte[] {LimboProtocol.VERSION}));
    }

    @Test
    void aWaitWithoutItsReasonDecodesToNothing() throws IOException {
        // The header says WAIT and the body is missing, the shape a half-flushed write produces.
        assertEquals(Optional.empty(), LimboProtocol.decode(raw(LimboProtocol.VERSION, (byte) 1)));
    }

    @Test
    void anotherVersionDecodesToNothing() throws IOException {
        assertEquals(Optional.empty(), LimboProtocol.decode(raw((byte) 2, (byte) 2)));
    }

    @Test
    void anUnknownMessageTypeDecodesToNothing() throws IOException {
        assertEquals(Optional.empty(), LimboProtocol.decode(raw(LimboProtocol.VERSION, (byte) 99)));
    }

    @Test
    void aReasonThisBuildDoesNotHaveDecodesToNothing() throws IOException {
        // A newer proxy's unknown reason is dropped, leaving the previous title standing.
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeByte(LimboProtocol.VERSION);
            out.writeByte(1);
            out.writeUTF("WORLD_GENERATING");
        }

        assertEquals(Optional.empty(), LimboProtocol.decode(bytes.toByteArray()));
    }

    @Test
    void parseIsTotalOverEveryNameAndRejectsTheRest() {
        for (final WaitReason reason : WaitReason.values()) {
            assertEquals(Optional.of(reason), WaitReason.parse(reason.name()));
        }
        assertEquals(Optional.empty(), WaitReason.parse(null));
        assertEquals(Optional.empty(), WaitReason.parse(""));
        // Deliberately case-sensitive: the enum constant's name is the protocol, not a label.
        assertEquals(Optional.empty(), WaitReason.parse("pack"));
    }

    @Test
    void theMessageRecordRefusesAWaitWithoutAReasonAndAReadyWithOne() {
        assertThrows(IllegalArgumentException.class, () -> new LimboProtocol.Message(LimboProtocol.Type.WAIT, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new LimboProtocol.Message(LimboProtocol.Type.READY, WaitReason.PACK));
    }

    private static byte[] raw(final byte... bytes) throws IOException {
        return bytes;
    }
}
