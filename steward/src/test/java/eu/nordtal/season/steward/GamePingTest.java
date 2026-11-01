package eu.nordtal.season.steward;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Checks that the game counts as up only when a status ping is answered, not when a port merely accepts. */
class GamePingTest {

    private final ExecutorService server = Executors.newSingleThreadExecutor();

    @AfterEach
    void stop() {
        server.shutdownNow();
    }

    @Test
    void aServerThatAnswersTheStatusPingIsUp() throws Exception {
        try (ServerSocket listening = new ServerSocket(0)) {
            final var _ = server.submit(() -> answerStatus(listening));
            assertTrue(ping(listening).answers());
        }
    }

    @Test
    void aPortThatAcceptsAndClosesIsDown() throws Exception {
        try (ServerSocket listening = new ServerSocket(0)) {
            final var _ = server.submit(() -> {
                try (Socket _ = listening.accept()) {
                    // Closes at once, as Caddy's game port guard does with no proxy behind it.
                }
                return null;
            });
            assertFalse(ping(listening).answers());
        }
    }

    @Test
    void aPortNobodyListensOnIsDown() throws Exception {
        final int port;
        try (ServerSocket gone = new ServerSocket(0)) {
            port = gone.getLocalPort();
        }
        assertFalse(new GamePing(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), Duration.ofSeconds(2))
                .answers());
    }

    private static GamePing ping(final ServerSocket listening) {
        return new GamePing(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), listening.getLocalPort()),
                Duration.ofSeconds(2));
    }

    /** Reads the handshake and the status request, then answers one status response. */
    private static Void answerStatus(final ServerSocket listening) throws IOException {
        try (Socket client = listening.accept()) {
            final DataInputStream in = new DataInputStream(client.getInputStream());
            in.readFully(new byte[readVarInt(in)]);
            in.readFully(new byte[readVarInt(in)]);
            final byte[] json =
                    "{\"version\":{\"name\":\"Velocity\",\"protocol\":767}}".getBytes(StandardCharsets.UTF_8);
            final ByteArrayOutputStream body = new ByteArrayOutputStream();
            body.write(0x00);
            writeVarInt(body, json.length);
            body.write(json);
            final OutputStream out = client.getOutputStream();
            writeVarInt(out, body.size());
            body.writeTo(out);
            out.flush();
        }
        return null;
    }

    private static int readVarInt(final DataInputStream in) throws IOException {
        int value = 0;
        for (int shift = 0; ; shift += 7) {
            final int next = in.readUnsignedByte();
            value |= (next & 0x7F) << shift;
            if ((next & 0x80) == 0) {
                return value;
            }
        }
    }

    private static void writeVarInt(final OutputStream out, final int value) throws IOException {
        int rest = value;
        while ((rest & ~0x7F) != 0) {
            out.write((rest & 0x7F) | 0x80);
            rest >>>= 7;
        }
        out.write(rest);
    }
}
