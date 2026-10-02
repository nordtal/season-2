package eu.nordtal.s2.stewardagent.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import eu.nordtal.s2.internalapi.agent.ImageResult;
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
 * Image drift against a hand-written daemon.
 *
 * A never-pushed local build still carries a {@code RepoDigests} entry under containerd; it is not outdated.
 */
class ContainersImagesTest {

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
    void anImageBuiltHereAndNeverPushedIsNotCalledOutdated() throws IOException {
        // A local build's RepoDigests carries one entry: the image's own id, not the registry's, same field as a pull.
        final String digest = "fe57c8bcb24535442a8abfc1eb070455ddb0918ccc94eeacd1be960a1ac5ea9b";
        final Containers ops = ops(request -> {
            if (request.contains("/images/")) {
                return "{\"RepoDigests\":[\"ghcr.io/nordtal/steward@sha256:" + digest + "\"],"
                        + "\"Identity\":{\"Build\":[{\"Ref\":\"it94gbngf02twhtf4mnkyk7ft\"}]}}";
            }
            if (request.contains("/distribution/")) {
                // What the registry last published: a different digest, since nothing built locally was pushed.
                return "{\"Descriptor\":{\"digest\":\"sha256:"
                        + "0000000000000000000000000000000000000000000000000000000000000000\"}}";
            }
            return null;
        });

        final Containers.ImageCheck check = ops.check("ghcr.io/nordtal/steward:latest", "sha256:" + digest);

        assertNotEquals(
                ImageResult.State.OUTDATED,
                check.state(),
                "a locally built, never-pushed image was reported OUTDATED - the registry has"
                        + " nothing newer, this host has something the registry has never seen: "
                        + check);
    }

    @Test
    void aLocallyBuiltImageIsReportedLocalNotUnknown() throws IOException {
        final String digest = "fe57c8bcb24535442a8abfc1eb070455ddb0918ccc94eeacd1be960a1ac5ea9b";
        final Containers ops = ops(request -> {
            if (request.contains("/images/")) {
                return "{\"RepoDigests\":[\"ghcr.io/nordtal/steward@sha256:" + digest + "\"],"
                        + "\"Identity\":{\"Build\":[{\"Ref\":\"it94gbngf02twhtf4mnkyk7ft\"}]}}";
            }
            if (request.contains("/distribution/")) {
                return "{\"Descriptor\":{\"digest\":\"sha256:"
                        + "0000000000000000000000000000000000000000000000000000000000000000\"}}";
            }
            return null;
        });

        final Containers.ImageCheck check = ops.check("ghcr.io/nordtal/steward:latest", "sha256:" + digest);

        assertEquals(ImageResult.State.LOCAL, check.state());
    }

    @Test
    void aGenuinelyPulledCurrentImageStillComesBackUpToDate() throws IOException {
        // The fix must not blunt the check that already works: a Pull image with a matching digest is still current.
        final String digest = "c36a132e4bfe218c139d1459b70049fe3e4dabf2c28bec2b725da73ce97c2cb4";
        final Containers ops = ops(request -> {
            if (request.contains("/images/")) {
                return "{\"RepoDigests\":[\"ghcr.io/nordtal/minecraft@sha256:" + digest + "\"],"
                        + "\"Identity\":{\"Pull\":[{\"Repository\":\"ghcr.io/nordtal/minecraft\"}]}}";
            }
            if (request.contains("/distribution/")) {
                return "{\"Descriptor\":{\"digest\":\"sha256:" + digest + "\"}}";
            }
            return null;
        });

        final Containers.ImageCheck check = ops.check("ghcr.io/nordtal/minecraft:latest", "sha256:" + digest);

        assertEquals(ImageResult.State.UP_TO_DATE, check.state());
    }

    @Test
    void anImageWhoseExactContentNoLongerExistsLocallyIsUnknownNotLocal() throws IOException {
        // The tag rebuilt locally while the container only restarted: the old image id 404s, garbage-collected.
        final Containers ops = ops(request -> request.contains("/images/") ? null : "{}");

        final Containers.ImageCheck check = ops.check("46fa95315f39", "sha256:" + "4".repeat(64));

        assertEquals(ImageResult.State.UNKNOWN, check.state());
    }

    /** A Containers whose daemon answers `/images/` and `/distribution/` through {@code answer}. */
    private Containers ops(final Answer answer) throws IOException {
        return new Containers(
                new Docker(new DockerSocket(
                        listening(request -> {
                            final String body = answer.to(request);
                            return body;
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
                    // Read the request first: a channel closed with unread bytes sends RST, which loses the reply.
                    final ByteBuffer buffer = ByteBuffer.allocate(8192);
                    client.read(buffer);
                    final String request = new String(buffer.flip().array(), 0, buffer.limit(), StandardCharsets.UTF_8);
                    final String body = answer.to(request);
                    final String head;
                    if (body == null) {
                        // 404, the shape a daemon sends for an image it no longer has.
                        final String notFound = "{\"message\":\"No such image\"}";
                        head = "HTTP/1.1 404 Not Found\r\nContent-Type: application/json\r\n"
                                + "Content-Length: " + notFound.getBytes(StandardCharsets.UTF_8).length
                                + "\r\n\r\n" + notFound;
                    } else {
                        head = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                                + body.getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n" + body;
                    }
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
        /** The JSON body for {@code request}, or {@code null} for a 404. */
        String to(String request);
    }
}
