package eu.nordtal.s2.stewardagent.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What this client does when the other end of the socket stops talking.
 *
 * These bind a socket and answer by hand, since a real daemon will not accept and then fall silent on request.
 */
class DockerSocketTest {

    @TempDir
    private Path directory;

    private final ExecutorService server = Executors.newCachedThreadPool();
    private final List<AutoCloseable> open = new ArrayList<>();

    @AfterEach
    void closeEverything() {
        server.shutdownNow();
        for (final AutoCloseable closeable : open) {
            try {
                closeable.close();
            } catch (Exception ignored) {
                // A test that is already over.
            }
        }
    }

    @Test
    void aStreamWhoseDaemonNeverAnswersGivesUpInsteadOfHangingWhoeverAsked() throws IOException {
        // Accepted, then silence (a daemon mid-restart or wedged) must not hang the request thread.
        final Path socket = listening(client -> {});

        final long before = System.nanoTime();
        assertThrows(
                DockerException.class,
                () -> new DockerSocket(socket, Duration.ofMillis(300)).stream("GET", "/containers/json", null));

        assertTrue(
                Duration.ofNanos(System.nanoTime() - before).toSeconds() < 10,
                "it gave up, but not within anything like the timeout it was given");
    }

    @Test
    void andOnceTheHeadersAreInAFollowMayBeSilentForLongerThanTheTimeout() throws Exception {
        // The watchdog is cancelled once the daemon answers: an hour of silence is a quiet server, not a fault.
        final Path socket = listening(client -> {
            write(client, "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n\r\n");
            Thread.sleep(900);
            write(client, "[04:45:12 INFO]: nothing happened for a while\n");
            client.shutdownOutput();
        });

        try (DockerSocket.Stream stream =
                new DockerSocket(socket, Duration.ofMillis(300)).stream("GET", "/logs", null)) {
            final List<String> lines = new ArrayList<>();
            LogFrames.read(stream.body(), false, lines::add);

            assertEquals(
                    List.of("[04:45:12 INFO]: nothing happened for a while"),
                    lines,
                    "three times the timeout of silence, and the line still arrived");
        }
    }

    /** A unix socket that accepts one connection and hands it to {@code answer}. */
    private Path listening(final Answer answer) throws IOException {
        final Path path = directory.resolve("docker.sock");
        final ServerSocketChannel socket = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        socket.bind(UnixDomainSocketAddress.of(path));
        open.add(socket);
        final var _ = server.submit(() -> {
            try (SocketChannel client = socket.accept()) {
                open.add(client);
                // Read the request before answering: a channel closed with unread bytes sends RST, losing the reply.
                client.read(ByteBuffer.allocate(4096));
                answer.answer(client);
                // Held open until the test is over: closing it early is a different failure from the one being tested.
                Thread.sleep(30_000);
            } catch (Exception ended) {
                // The test finished and shut the pool down.
            }
            return null;
        });
        return path;
    }

    private static void write(final SocketChannel client, final String text) throws IOException {
        client.write(ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8)));
    }

    @FunctionalInterface
    private interface Answer {
        void answer(SocketChannel client) throws Exception;
    }
}
