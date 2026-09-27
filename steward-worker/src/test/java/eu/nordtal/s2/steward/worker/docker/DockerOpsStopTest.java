package eu.nordtal.s2.steward.worker.docker;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.steward.worker.ops.RedeployResult;
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
 * How a stop ended, which the stop call itself does not say.
 *
 * A hand-written daemon reports the exit code at once, where a real one would wait out the grace period.
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
    void aContainerDockerHadToKillIsARefusedStopNotASuccessfulOne() throws IOException {
        // 137 is SIGKILL after the grace period ran out mid-write: a world half saved, then tarred as a backup.
        final RedeployResult result = ops(137).stop("smp-container");

        assertFalse(result.triggered(), result.message());
        assertTrue(result.message().contains("killed"), result.message());
        assertTrue(
                result.message().contains("backup"),
                "the message has to say what it costs, not just what happened: " + result.message());
    }

    @Test
    void anOrdinaryShutdownIsAnOrdinaryStop() throws IOException {
        final RedeployResult result = ops(0).stop("smp-container");

        assertTrue(result.triggered());
        assertTrue(
                result.verified(),
                "the ending was read and it was a clean one, so nothing"
                        + " downstream has any reason to doubt the backup that follows");
    }

    @Test
    void aContainerThatHasNotExitedAtAllIsNotReadAsKilled() throws IOException {
        // -1 is "the daemon said nothing about it"; refusing on that would fail an older daemon lacking the field.
        final RedeployResult result = ops(null).stop("smp-container");

        assertTrue(result.triggered());
        assertTrue(
                result.verified(),
                "the inspect answered; an old daemon leaving the field out is"
                        + " not the same as an inspect nobody could read");
    }

    @Test
    void aStopWhoseEndingCouldNotBeReadIsNeitherAFailureNorAnOrdinaryStop() throws IOException {
        // The daemon accepts the stop, then stops answering: refusing here is worse than marking the result unverified.
        final RedeployResult result = deafAfterTheStop().stop("smp-container");

        assertTrue(result.triggered(), "the stop itself worked, and the container really is down: " + result.message());
        assertFalse(
                result.verified(),
                "not knowing how it ended is not the same as knowing it"
                        + " ended well, and the backup taken over it is marked on the strength of this"
                        + " one bit: " + result.message());
        assertTrue(
                result.message().contains("could not be read back"),
                "the sentence ends up on the report line and in the mark beside the archive, so it"
                        + " has to say what was not read: " + result.message());
    }

    /** A DockerOps whose daemon accepts the stop and then hangs up on the inspect. */
    private DockerOps deafAfterTheStop() throws IOException {
        return new DockerOps(
                new Docker(new DockerSocket(
                        listening(request -> request.contains("/stop") ? "" : null), Duration.ofSeconds(5))),
                "nordtal-s2");
    }

    /** A DockerOps whose daemon accepts the stop and then reports {@code exitCode}. */
    private DockerOps ops(final Integer exitCode) throws IOException {
        final String state =
                exitCode == null ? "{\"Status\":\"exited\"}" : "{\"Status\":\"exited\",\"ExitCode\":" + exitCode + "}";
        return new DockerOps(
                new Docker(new DockerSocket(
                        listening(request -> {
                            if (request.contains("/stop")) {
                                return "";
                            }
                            if (request.contains("/images/")) {
                                return "{\"RepoDigests\":[]}";
                            }
                            return "{\"Id\":\"smp-container\",\"Image\":\"sha256:1\",\"State\":" + state
                                    + ",\"Config\":{\"Image\":\"nordtal/smp\",\"Tty\":false}}";
                        }),
                        Duration.ofSeconds(5))),
                "nordtal-s2");
    }

    /** A unix socket that answers every request, one connection at a time, until the test ends. */
    private Path listening(final Answer answer) throws IOException {
        final Path path = directory.resolve("docker.sock");
        final ServerSocketChannel socket = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        socket.bind(UnixDomainSocketAddress.of(path));
        open.add(socket);
        final var _ = server.submit(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try (SocketChannel client = socket.accept()) {
                    // Read the request first: a channel closed with unread bytes sends RST, losing the reply.
                    final ByteBuffer buffer = ByteBuffer.allocate(8192);
                    client.read(buffer);
                    final String request = new String(buffer.flip().array(), 0, buffer.limit(), StandardCharsets.UTF_8);
                    final String body = answer.to(request);
                    if (body == null) {
                        // A daemon that died between two calls looks like this: connection taken, then end-of-stream.
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
        /** The JSON body, an empty string for a 204, or {@code null} to hang up unanswered. */
        String to(String request);
    }
}
