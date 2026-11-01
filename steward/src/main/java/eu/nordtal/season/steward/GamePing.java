package eu.nordtal.season.steward;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Sends a Minecraft status ping to the game port and says whether a proxy answered it.
 *
 * A port that only accepts the connection does not count: Caddy's game port guard does that with no proxy behind it.
 */
public final class GamePing {

    /** Caddy's game port inside the network, so the ping takes the way a player's does. */
    public static final InetSocketAddress CADDY = InetSocketAddress.createUnresolved("caddy", 25565);

    /** Longer than Caddy's {@code lb_try_duration}, so a switch to the standby still counts as an answer. */
    public static final Duration CADDY_FAILOVER = Duration.ofSeconds(7);

    // The status ping is the same in every protocol version; -1 asks for none in particular.
    private static final int ANY_PROTOCOL = -1;
    private static final int NEXT_STATE_STATUS = 1;
    private static final int LONGEST_PACKET = 1 << 21;

    private final InetSocketAddress address;
    private final Duration timeout;

    public GamePing(final InetSocketAddress address, final Duration timeout) {
        this.address = address;
        this.timeout = timeout;
    }

    /** Returns whether a status response came back within the timeout. */
    public boolean answers() {
        final InetSocketAddress target =
                address.isUnresolved() ? new InetSocketAddress(address.getHostString(), address.getPort()) : address;
        if (target.isUnresolved()) {
            return false;
        }
        try (Socket socket = new Socket()) {
            final int millis = Math.toIntExact(timeout.toMillis());
            socket.connect(target, millis);
            socket.setSoTimeout(millis);
            final OutputStream out = socket.getOutputStream();
            writePacket(out, handshake(target));
            writePacket(out, new byte[] {0x00});
            out.flush();
            return isStatusResponse(new DataInputStream(socket.getInputStream()));
        } catch (IOException | ArithmeticException noAnswer) {
            return false;
        }
    }

    private static byte[] handshake(final InetSocketAddress target) throws IOException {
        final byte[] host = target.getHostString().getBytes(StandardCharsets.UTF_8);
        final ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(0x00);
        writeVarInt(body, ANY_PROTOCOL);
        writeVarInt(body, host.length);
        body.write(host);
        body.write(target.getPort() >> 8);
        body.write(target.getPort());
        writeVarInt(body, NEXT_STATE_STATUS);
        return body.toByteArray();
    }

    private static boolean isStatusResponse(final DataInputStream in) throws IOException {
        final int length = readVarInt(in);
        if (length < 2 || length > LONGEST_PACKET || readVarInt(in) != 0x00) {
            return false;
        }
        final int json = readVarInt(in);
        return json > 0 && in.readUnsignedByte() == '{';
    }

    private static void writePacket(final OutputStream out, final byte[] body) throws IOException {
        writeVarInt(out, body.length);
        out.write(body);
    }

    private static void writeVarInt(final OutputStream out, final int value) throws IOException {
        int rest = value;
        while ((rest & ~0x7F) != 0) {
            out.write((rest & 0x7F) | 0x80);
            rest >>>= 7;
        }
        out.write(rest);
    }

    private static int readVarInt(final DataInputStream in) throws IOException {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            final int next = in.readUnsignedByte();
            value |= (next & 0x7F) << shift;
            if ((next & 0x80) == 0) {
                return value;
            }
        }
        throw new IOException("a VarInt longer than five bytes");
    }
}
