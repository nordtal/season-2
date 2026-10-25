package eu.nordtal.season.bridgemod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ConnectException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class BridgeServerTest {

    private final List<Request> carriedOut = new CopyOnWriteArrayList<>();

    /** Collects what the server sends, a close as "closed CODE". */
    private static final class Received implements WebSocket.Listener {
        final LinkedBlockingQueue<String> frames = new LinkedBlockingQueue<>();

        @Override
        public CompletionStage<?> onText(final WebSocket socket, final CharSequence data, final boolean last) {
            frames.add(data.toString());
            socket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(final WebSocket socket, final int status, final String reason) {
            frames.add("closed " + status);
            return null;
        }

        String next() throws InterruptedException {
            final String frame = frames.poll(10, TimeUnit.SECONDS);
            return frame == null ? "nothing" : frame;
        }
    }

    private BridgeServer server() throws InterruptedException {
        return new BridgeServer(0, Duration.ofMillis(300), "secret"::equals, request -> {
            carriedOut.add(request);
            return CompletableFuture.completedFuture(null);
        });
    }

    private static WebSocket connect(final BridgeServer server, final Received received) {
        return HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .buildAsync(URI.create("ws://127.0.0.1:" + server.port() + "/"), received)
                .join();
    }

    @Test
    void aClientWithTheTokenGetsItsRequestsCarriedOut() throws InterruptedException {
        try (BridgeServer server = server()) {
            final Received received = new Received();
            final WebSocket socket = connect(server, received);
            socket.sendText("{\"op\":\"hello\",\"token\":\"secret\"}", true).join();
            assertEquals("{\"ok\":true}", received.next());
            socket.sendText("{\"id\":7,\"op\":\"actionbar\",\"text\":\"hi\"}", true)
                    .join();
            assertEquals("{\"id\":7,\"ok\":true}", received.next());
            assertEquals(1, carriedOut.size());
        }
    }

    @Test
    void aClientWithAWrongTokenIsClosedAsAPolicyViolation() throws InterruptedException {
        try (BridgeServer server = server()) {
            final Received received = new Received();
            connect(server, received)
                    .sendText("{\"op\":\"hello\",\"token\":\"guess\"}", true)
                    .join();
            assertEquals(
                    "{\"ok\":false,\"error\":\"The token does not match the one in config/nordtal-bridge.properties.\"}",
                    received.next());
            assertEquals("closed 1008", received.next());
            assertEquals(List.of(), carriedOut);
        }
    }

    @Test
    void aClientThatSendsNoHelloIsClosedAfterTheWait() throws InterruptedException {
        try (BridgeServer server = server()) {
            final Received received = new Received();
            connect(server, received);
            assertEquals("closed 1008", received.next());
        }
    }

    @Test
    void aClientWithTheTokenStaysConnectedPastTheWait() throws InterruptedException {
        try (BridgeServer server = server()) {
            final Received received = new Received();
            connect(server, received)
                    .sendText("{\"op\":\"hello\",\"token\":\"secret\"}", true)
                    .join();
            assertEquals("{\"ok\":true}", received.next());
            assertNull(received.frames.poll(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void theServerListensOnTheLoopbackAddressOnly() throws Exception {
        try (BridgeServer server = server()) {
            final InetAddress other = NetworkInterface.networkInterfaces()
                    .flatMap(NetworkInterface::inetAddresses)
                    .filter(address -> !address.isLoopbackAddress() && address instanceof Inet4Address)
                    .findFirst()
                    .orElse(null);
            Assumptions.assumeTrue(other != null, "This host has no address beside the loopback one.");
            try (Socket loopback = new Socket(InetAddress.getLoopbackAddress(), server.port())) {
                assertTrue(loopback.isConnected());
            }
            assertThrows(ConnectException.class, () -> new Socket(other, server.port()).close());
        }
    }
}
