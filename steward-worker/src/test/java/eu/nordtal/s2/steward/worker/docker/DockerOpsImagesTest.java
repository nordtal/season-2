package eu.nordtal.s2.steward.worker.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import eu.nordtal.s2.steward.worker.ops.ImageResult;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Image drift against a hand-written daemon, for the one case a real daemon on this host cannot be
 * asked to hold still for: a locally built image that never went near a registry (steward/75).
 *
 * <h2>Why this needs a fake daemon and not {@code DockerIntegrationTest}</h2>
 * The bug this file is red against is not "what does dockerd send back" - {@code DockerIntegrationTest}
 * already covers that ground and does it against the real socket. It is "what does {@code DockerOps}
 * do with a shape dockerd sends back on THIS host that the check never expected": Docker Engine
 * 29.8.0 here uses the containerd-backed image store ({@code driver-type: io.containerd.snapshotter.v1},
 * measured 2026-09-16 with {@code docker info}), and under it a locally built, never-pushed image
 * still carries a {@code RepoDigests} entry - one equal to the image's own content id, never
 * confirmed by any registry round trip. {@code steward-ui} and {@code steward-worker} were rebuilt
 * exactly that way on 2026-09-16 and both came back {@code OUTDATED}, the opposite of the truth: they
 * are ahead of the registry, not behind it. A real daemon on this host cannot be asked to hold that
 * shape still across a build; a hand-written one can.
 */
class DockerOpsImagesTest {

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
    @DisplayName("an image built here and never pushed is not called outdated")
    void aLocallyBuiltImageIsNotCalledOutdated() throws IOException {
        // The exact shape measured on this host on 2026-09-16 for ghcr.io/nordtal/steward-ui:latest
        // after `docker build -t ... .` and `up -d --force-recreate`: RepoDigests carries one entry,
        // and its digest is the image's own id - not the registry's - because the containerd image
        // store writes that entry for a build exactly as it does for a pull.
        final String digest = "fe57c8bcb24535442a8abfc1eb070455ddb0918ccc94eeacd1be960a1ac5ea9b";
        final DockerOps ops = ops(request -> {
            if (request.contains("/images/")) {
                return "{\"RepoDigests\":[\"ghcr.io/nordtal/steward-ui@sha256:" + digest + "\"],"
                        + "\"Identity\":{\"Build\":[{\"Ref\":\"it94gbngf02twhtf4mnkyk7ft\"}]}}";
            }
            if (request.contains("/distribution/")) {
                // What the registry actually last published - a different digest, because nothing
                // built on this host was ever pushed to it.
                return "{\"Descriptor\":{\"digest\":\"sha256:"
                        + "0000000000000000000000000000000000000000000000000000000000000000\"}}";
            }
            return null;
        });

        final DockerOps.ImageCheck check = ops.check("ghcr.io/nordtal/steward-ui:latest", "sha256:" + digest);

        assertNotEquals(
                ImageResult.State.OUTDATED,
                check.state(),
                "a locally built, never-pushed image was reported OUTDATED - the registry has"
                        + " nothing newer, this host has something the registry has never seen: "
                        + check);
    }

    @Test
    @DisplayName("a locally built image is reported LOCAL, not UNKNOWN")
    void aLocallyBuiltImageIsLocal() throws IOException {
        final String digest = "fe57c8bcb24535442a8abfc1eb070455ddb0918ccc94eeacd1be960a1ac5ea9b";
        final DockerOps ops = ops(request -> {
            if (request.contains("/images/")) {
                return "{\"RepoDigests\":[\"ghcr.io/nordtal/steward-ui@sha256:" + digest + "\"],"
                        + "\"Identity\":{\"Build\":[{\"Ref\":\"it94gbngf02twhtf4mnkyk7ft\"}]}}";
            }
            if (request.contains("/distribution/")) {
                return "{\"Descriptor\":{\"digest\":\"sha256:"
                        + "0000000000000000000000000000000000000000000000000000000000000000\"}}";
            }
            return null;
        });

        final DockerOps.ImageCheck check = ops.check("ghcr.io/nordtal/steward-ui:latest", "sha256:" + digest);

        assertEquals(ImageResult.State.LOCAL, check.state());
    }

    @Test
    @DisplayName("a genuinely pulled, current image still comes back UP_TO_DATE")
    void aPulledCurrentImageIsStillUpToDate() throws IOException {
        // The fix must not blunt the check that already works: an image with Identity.Pull (never
        // Identity.Build) and a matching digest is still current.
        final String digest = "c36a132e4bfe218c139d1459b70049fe3e4dabf2c28bec2b725da73ce97c2cb4";
        final DockerOps ops = ops(request -> {
            if (request.contains("/images/")) {
                return "{\"RepoDigests\":[\"ghcr.io/nordtal/minecraft@sha256:" + digest + "\"],"
                        + "\"Identity\":{\"Pull\":[{\"Repository\":\"ghcr.io/nordtal/minecraft\"}]}}";
            }
            if (request.contains("/distribution/")) {
                return "{\"Descriptor\":{\"digest\":\"sha256:" + digest + "\"}}";
            }
            return null;
        });

        final DockerOps.ImageCheck check = ops.check("ghcr.io/nordtal/minecraft:latest", "sha256:" + digest);

        assertEquals(ImageResult.State.UP_TO_DATE, check.state());
    }

    @Test
    @DisplayName("an image whose exact content no longer exists locally is UNKNOWN, not LOCAL")
    void aVanishedImageIsUnknown() throws IOException {
        // discord-bot's neighbouring case: the tag was rebuilt locally while the container was only
        // restarted, so the container's own image id has been garbage-collected and 404s.
        final DockerOps ops = ops(request -> request.contains("/images/") ? null : "{}");

        final DockerOps.ImageCheck check = ops.check("46fa95315f39", "sha256:" + "4".repeat(64));

        assertEquals(ImageResult.State.UNKNOWN, check.state());
    }

    /** A DockerOps whose daemon answers `/images/` and `/distribution/` through {@code answer}. */
    private DockerOps ops(final Answer answer) throws IOException {
        return new DockerOps(
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
        server.submit(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try (SocketChannel client = socket.accept()) {
                    // Read the request before answering: a channel closed with unread inbound bytes
                    // sends RST, and the RST throws away the answer just written.
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
        /** @return the JSON body, or {@code null} for a 404 - what a daemon sends for a gone image */
        String to(String request);
    }
}
