package eu.nordtal.s2.steward.worker.docker;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What this client does when the thing on the other end of the socket stops talking.
 *
 * <p>No docker daemon: a unix socket is a unix socket, so these bind one and answer it by hand -
 * which is the only way to produce a daemon that accepts a connection and then says nothing, the
 * case the timeout exists for and the one a real daemon will not perform on request.</p>
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
    @DisplayName("a stream whose daemon never answers gives up, instead of hanging whoever asked")
    void establishingAStreamIsWatched() throws IOException {
        // Accepted and then silence - a daemon mid-restart, or one wedged on its own lock. The
        // stream() overload without a deadline used to set no watchdog at all, so this call never
        // returned: the browser waiting on /api/services/smp/logs simply never got a response, and
        // the request thread was gone for good.
        final Path socket = listening(client -> { });

        final long before = System.nanoTime();
        assertThrows(DockerException.class,
                () -> new DockerSocket(socket, Duration.ofMillis(300)).stream("GET", "/containers/json", null));

        assertTrue(Duration.ofNanos(System.nanoTime() - before).toSeconds() < 10,
                "it gave up, but not within anything like the timeout it was given");
    }

    @Test
    @DisplayName("and once the headers are in, a follow may be silent for longer than the timeout")
    void aFollowIsNotWatchedAfterThat() throws Exception {
        // The other half of the same change: the alarm is cancelled when the daemon answers. A log
        // follow that says nothing for an hour is not a fault, it is a quiet server.
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

            assertEquals(List.of("[04:45:12 INFO]: nothing happened for a while"), lines,
                    "three times the timeout of silence, and the line still arrived");
        }
    }

    /** A unix socket that accepts one connection and hands it to {@code answer}. */
    private Path listening(final Answer answer) throws IOException {
        final Path path = directory.resolve("docker.sock");
        final ServerSocketChannel socket = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        socket.bind(UnixDomainSocketAddress.of(path));
        open.add(socket);
        server.submit(() -> {
            try (SocketChannel client = socket.accept()) {
                open.add(client);
                // The request has to be taken off the socket before anything is closed: a channel
                // closed with unread inbound bytes sends RST, and the RST throws away the answer
                // this test just wrote.
                client.read(ByteBuffer.allocate(4096));
                answer.answer(client);
                // Held open until the test is over: closing it early is a different failure from
                // the one being tested.
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
