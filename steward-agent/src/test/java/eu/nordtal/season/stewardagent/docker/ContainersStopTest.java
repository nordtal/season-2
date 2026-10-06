package eu.nordtal.season.stewardagent.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.ComposeFile;
import eu.nordtal.season.common.time.TestScheduler;
import eu.nordtal.season.internalapi.agent.RedeployResult;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * How a stop ended, which the stop call itself does not say.
 *
 * A hand-written daemon reports the exit code at once, where a real one would wait out the grace period.
 */
class ContainersStopTest {

    @TempDir
    private Path directory;

    private final ExecutorService server = Executors.newCachedThreadPool();
    private final List<AutoCloseable> open = new ArrayList<>();
    private final List<String> stops = new CopyOnWriteArrayList<>();

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

    /** A one-shot is judged by how its last run ended, so the table carries when that was and the code. */
    @Test
    void aStoppedContainerSaysWhenItEndedAndWithWhatCodeAndOneThatNeverRanSaysNeither() throws IOException {
        final String finished = "{\"Status\":\"exited\",\"ExitCode\":3,\"StartedAt\":\"2026-10-06T01:00:00Z\","
                + "\"FinishedAt\":\"2026-10-06T01:00:09Z\"}";
        final String never = "{\"Status\":\"created\",\"ExitCode\":0,\"FinishedAt\":\"0001-01-01T00:00:00Z\"}";
        final var listed = new Containers(
                        new Docker(new DockerSocket(
                                listening(request -> {
                                    if (request.startsWith("GET /containers/json")) {
                                        return "[" + listed("migrate") + "," + listed("standby") + "]";
                                    }
                                    return inspection(request.contains("/migrate/") ? finished : never, 10);
                                }),
                                Duration.ofSeconds(5),
                                TestScheduler.SHARED)),
                        "nordtal-s2")
                .list(java.util.Map.of())
                .containers();

        assertEquals("2026-10-06T01:00:09Z", listed.get(0).finishedAt());
        assertEquals(3, listed.get(0).exitCode());
        assertEquals(null, listed.get(1).finishedAt());
        assertEquals(null, listed.get(1).exitCode(), "a container that never ran has no exit code, not a zero");
    }

    private static String listed(final String service) {
        return "{\"Id\":\"" + service + "\",\"Names\":[\"/nordtal-s2-" + service + "-1\"],\"Image\":\"i\","
                + "\"ImageID\":\"sha256:1\",\"State\":\"exited\",\"Status\":\"Exited\",\"Labels\":"
                + "{\"com.docker.compose.project\":\"nordtal-s2\",\"com.docker.compose.service\":\"" + service + "\"}}";
    }

    @Test
    void theStopWaitsTheGraceComposeGaveTheContainer() throws IOException {
        // compose writes stop_grace_period into the container's StopTimeout; a server saving its world needs all of it.
        ops(0, 180).stop("smp-container");

        assertEquals(List.of("t=180"), stops);
    }

    @Test
    void aContainerWithoutAGraceOfItsOwnGetsDockersDefault() throws IOException {
        ops(0, null).stop("smp-container");

        assertEquals(List.of("t=" + Docker.DEFAULT_STOP_TIMEOUT), stops);
    }

    @Test
    void aGraceLongerThanTheClientWaitsIsCutToWhatItWaits() throws IOException {
        // Past the client's wait a stop that is still saving would be reported as failed, and the run would go on.
        ops(0, 3600).stop("smp-container");

        assertEquals(List.of("t=" + Containers.LONGEST_GRACE_SECONDS), stops);
    }

    @Test
    void everyGraceInComposeFitsInsideTheClientsWait() {
        final Pattern grace = Pattern.compile("(\\d+)(s|m)");
        int found = 0;
        for (final ComposeFile.Service service : ComposeFile.get().services().values()) {
            final String written = service.text("stop_grace_period").orElse(null);
            if (written == null) {
                continue;
            }
            final Matcher parts = grace.matcher(written);
            assertTrue(parts.matches(), service.name() + " writes stop_grace_period as '" + written + "'");
            found++;
            final long seconds = Long.parseLong(parts.group(1)) * ("m".equals(parts.group(2)) ? 60 : 1);
            assertTrue(
                    seconds <= Containers.LONGEST_GRACE_SECONDS,
                    service.name() + " gets " + seconds + " s to stop, but a stop through the agent is cut at "
                            + Containers.LONGEST_GRACE_SECONDS + " s; raise AgentWire.LONGEST_STOP with it");
        }
        assertTrue(found > 0, "compose.yml no longer sets any stop_grace_period, so this holds nothing");
    }

    /** A Containers whose daemon answers the inspect before the stop, accepts the stop, then hangs up on the next. */
    private Containers deafAfterTheStop() throws IOException {
        return new Containers(
                new Docker(new DockerSocket(
                        listening(request -> {
                            if (request.contains("/stop")) {
                                stops.add(request);
                                return "";
                            }
                            return stops.isEmpty() ? inspection("{\"Status\":\"running\"}", 30) : null;
                        }),
                        Duration.ofSeconds(5),
                        TestScheduler.SHARED)),
                "nordtal-s2");
    }

    /** A Containers whose daemon accepts the stop and then reports {@code exitCode}. */
    private Containers ops(final Integer exitCode) throws IOException {
        return ops(exitCode, 30);
    }

    /** The same, for a container compose created with {@code stopTimeout} seconds of grace, or none. */
    private Containers ops(final Integer exitCode, final Integer stopTimeout) throws IOException {
        final String state =
                exitCode == null ? "{\"Status\":\"exited\"}" : "{\"Status\":\"exited\",\"ExitCode\":" + exitCode + "}";
        return new Containers(
                new Docker(new DockerSocket(
                        listening(request -> {
                            if (request.contains("/stop")) {
                                final Matcher t =
                                        Pattern.compile("[?&](t=\\d+)").matcher(request);
                                stops.add(t.find() ? t.group(1) : "no t");
                                return "";
                            }
                            if (request.contains("/images/")) {
                                return "{\"RepoDigests\":[]}";
                            }
                            return inspection(state, stopTimeout);
                        }),
                        Duration.ofSeconds(5),
                        TestScheduler.SHARED)),
                "nordtal-s2");
    }

    private static String inspection(final String state, final Integer stopTimeout) {
        return "{\"Id\":\"smp-container\",\"Image\":\"sha256:1\",\"State\":" + state
                + ",\"Config\":{\"Image\":\"nordtal/smp\",\"Tty\":false"
                + (stopTimeout == null ? "" : ",\"StopTimeout\":" + stopTimeout) + "}}";
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
