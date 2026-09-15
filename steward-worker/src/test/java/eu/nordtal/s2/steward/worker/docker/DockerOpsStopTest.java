package eu.nordtal.s2.steward.worker.docker;

import eu.nordtal.s2.steward.worker.ops.RedeployResult;
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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How a stop ended, which the stop call itself does not say.
 *
 * <h2>Why a hand-written daemon</h2>
 * The case is a container that ignores SIGTERM until Docker kills it, and a real daemon will only
 * perform that after the full grace period - thirty seconds of test, every build, to observe one
 * integer. The integer is all that is being read here, so the daemon is a unix socket answering two
 * requests by hand. What the real one puts in that field is checked by {@code DockerIntegrationTest}
 * against a container that really did exit.
 */
class DockerOpsStopTest {

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
    @DisplayName("a container docker had to kill is a refused stop, not a successful one")
    void aKilledContainerIsNotAStop() throws IOException {
        // 137 is SIGKILL, and after a `docker stop` it means one thing: the grace period ran out
        // while the process was still working. For a Minecraft server that is a world half saved -
        // and the next thing the sequence does is tar that volume and call the result a backup.
        final RedeployResult result = ops(137).stop("smp-container");

        assertFalse(result.triggered(), result.message());
        assertTrue(result.message().contains("killed"), result.message());
        assertTrue(result.message().contains("backup"),
                "the message has to say what it costs, not just what happened: " + result.message());
    }

    @Test
    @DisplayName("an ordinary shutdown is an ordinary stop")
    void anOrdinaryExitIsAStop() throws IOException {
        final RedeployResult result = ops(0).stop("smp-container");

        assertTrue(result.triggered());
        assertTrue(result.verified(), "the ending was read and it was a clean one, so nothing"
                + " downstream has any reason to doubt the backup that follows");
    }

    @Test
    @DisplayName("a container that has not exited at all is not read as killed")
    void aMissingExitCodeIsNotAKill() throws IOException {
        // -1 is this client's "the daemon said nothing about it". Refusing on that would take a
        // network down over a field an older daemon does not send.
        final RedeployResult result = ops(null).stop("smp-container");

        assertTrue(result.triggered());
        assertTrue(result.verified(), "the inspect answered; an old daemon leaving the field out is"
                + " not the same as an inspect nobody could read");
    }

    @Test
    @DisplayName("a stop whose ending could not be read is neither a failure nor an ordinary stop")
    void anUnreadableInspectIsAnUnverifiedStop() throws IOException {
        // The daemon accepts the stop and then stops answering, which is run 23's shape: the
        // container is down, and whether the world had finished writing is a question nobody can
        // answer any more. Refusing would take the network down over an unreadable inspect; calling
        // it a success is what let the archive taken afterwards look like every other archive.
        final RedeployResult result = deafAfterTheStop().stop("smp-container");

        assertTrue(result.triggered(), "the stop itself worked, and the container really is down: "
                + result.message());
        assertFalse(result.verified(), "not knowing how it ended is not the same as knowing it"
                + " ended well, and the backup taken over it is marked on the strength of this"
                + " one bit: " + result.message());
        assertTrue(result.message().contains("could not be read back"),
                "the sentence ends up on the report line and in the mark beside the archive, so it"
                        + " has to say what was not read: " + result.message());
    }

    /** A DockerOps whose daemon accepts the stop and then hangs up on the inspect. */
    private DockerOps deafAfterTheStop() throws IOException {
        return new DockerOps(new Docker(new DockerSocket(listening(request ->
                request.contains("/stop") ? "" : null), Duration.ofSeconds(5))), "nordtal-s2");
    }

    /** A DockerOps whose daemon accepts the stop and then reports {@code exitCode}. */
    private DockerOps ops(final Integer exitCode) throws IOException {
        final String state = exitCode == null
                ? "{\"Status\":\"exited\"}"
                : "{\"Status\":\"exited\",\"ExitCode\":" + exitCode + "}";
        return new DockerOps(new Docker(new DockerSocket(listening(request -> {
            if (request.contains("/stop")) {
                return "";
            }
            if (request.contains("/images/")) {
                return "{\"RepoDigests\":[]}";
            }
            return "{\"Id\":\"smp-container\",\"Image\":\"sha256:1\",\"State\":" + state
                    + ",\"Config\":{\"Image\":\"nordtal/smp\",\"Tty\":false}}";
        }), Duration.ofSeconds(5))), "nordtal-s2");
    }

    /** A unix socket that answers every request, one connection at a time, until the test ends. */
    private Path listening(final Answer answer) throws IOException {
        final Path path = directory.resolve("docker.sock");
        final ServerSocketChannel socket = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        socket.bind(UnixDomainSocketAddress.of(path));
        open.add(socket);
        server.submit(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try (SocketChannel client = socket.accept()) {
                    // Read the request before answering: a channel closed with unread inbound bytes
                    // sends RST, and the RST throws away the answer just written.
                    final ByteBuffer buffer = ByteBuffer.allocate(8192);
                    client.read(buffer);
                    final String request = new String(buffer.flip().array(), 0, buffer.limit(),
                            StandardCharsets.UTF_8);
                    final String body = answer.to(request);
                    if (body == null) {
                        // A daemon that took the connection and then said nothing: the client reads
                        // end-of-stream where a status line should be. That is what a dockerd which
                        // died between two calls looks like from this side, and it is the only way
                        // to reach the unverified branch without a sleep.
                        continue;
                    }
                    final String head = body.isEmpty()
                            ? "HTTP/1.1 204 No Content\r\nContent-Length: 0\r\n\r\n"
                            : "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                              + body.getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n" + body;
                    client.write(ByteBuffer.wrap(head.getBytes(StandardCharsets.UTF_8)));
                } catch (Exception ended) {
                    return null;
                }
            }
            return null;
        });
        return path;
    }

    @FunctionalInterface
    private interface Answer {
        /** @return the JSON body, the empty string for a 204, or {@code null} to hang up unanswered */
        String to(String request);
    }
}
