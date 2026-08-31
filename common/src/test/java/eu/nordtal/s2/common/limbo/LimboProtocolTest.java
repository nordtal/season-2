package eu.nordtal.s2.common.limbo;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code nordtal:limbo} wire format, from both ends.
 * <p>
 * This is the one piece of the login path that two separate processes have to agree on byte for
 * byte, and the failure mode when they do not is silence: a message that does not parse is
 * indistinguishable from one that was never sent, so a player simply sits in the waiting room
 * forever with nothing in any log. Round-tripping every message here is what makes that
 * impossible to introduce by accident.
 * </p>
 * <p>
 * The other half of the contract is that {@link LimboProtocol#decode(byte[])} never throws. Every
 * byte array it sees came off a socket - and on the proxy, off one a modded client can write to -
 * so the malformed cases below are not defensive padding, they are the ordinary input.
 * </p>
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
        final LimboProtocol.Message message = LimboProtocol.decode(LimboProtocol.ready()).orElseThrow();

        assertEquals(LimboProtocol.Type.READY, message.type());
        assertEquals(null, message.reason());
    }

    @Test
    void aWaitIsTwoBytesOfHeaderAndReadyIsNothingElse() {
        // The header is the compatibility surface: version, then type. If either moves, a proxy and
        // a backend of different versions stop understanding each other, and this assertion is what
        // makes that a failing test rather than a silent waiting room.
        assertEquals(LimboProtocol.VERSION, LimboProtocol.ready()[0]);
        assertEquals(2, LimboProtocol.ready().length);
        assertEquals(LimboProtocol.VERSION, LimboProtocol.wait(WaitReason.PACK)[0]);
    }

    @Test
    void nullAndTruncatedPayloadsDecodeToNothing() {
        assertEquals(Optional.empty(), LimboProtocol.decode(null));
        assertEquals(Optional.empty(), LimboProtocol.decode(new byte[0]));
        assertEquals(Optional.empty(), LimboProtocol.decode(new byte[]{LimboProtocol.VERSION}));
    }

    @Test
    void aWaitWithoutItsReasonDecodesToNothing() throws IOException {
        // The header says WAIT and the body is missing - the shape a half-flushed write produces.
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
        // What a newer proxy sending a fourth reason looks like to an older limbo. Dropping it
        // leaves the previous title standing, which is a stale screen rather than a broken one.
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
        assertThrows(IllegalArgumentException.class,
                () -> new LimboProtocol.Message(LimboProtocol.Type.WAIT, null));
        assertThrows(IllegalArgumentException.class,
                () -> new LimboProtocol.Message(LimboProtocol.Type.READY, WaitReason.PACK));
    }

    @Test
    void everyReasonNamesAMessageKeyUnderItsOwnPrefix() {
        assertEquals("limbo.wait.pack.title", WaitReason.PACK.titleKey());
        assertEquals("limbo.wait.pack.subtitle", WaitReason.PACK.subtitleKey());
        assertEquals("limbo.wait.maintenance.title", WaitReason.MAINTENANCE.titleKey());
    }

    private static byte[] raw(final byte... bytes) throws IOException {
        return bytes;
    }
}
