package eu.nordtal.s2.steward.worker.docker;

import eu.nordtal.s2.steward.worker.ops.ImageResult;
import eu.nordtal.s2.steward.worker.ops.RuntimeResult;
import eu.nordtal.s2.steward.worker.ops.ServiceRuntime;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The docker client against a real daemon, because nothing else proves it.
 *
 * <h2>Why this is not a unit test with a canned response</h2>
 * Every bug this layer can have is a bug about what the daemon actually sends: a chunked body, an
 * eight-byte frame header that is there or is not, a stats sample whose {@code precpu} is zero, a
 * registry that answers a digest for one reference and nothing for another. A fixture proves that
 * the parser agrees with the fixture. So the assertions here compare this client with the
 * {@code docker} command line reading the same daemon - two independent readers of one truth.
 *
 * <p>It skips itself where there is no socket, which is every laptop and every CI runner without
 * one. On the dev server, where it was written, it runs.</p>
 */
class DockerIntegrationTest {

    private static final String PROJECT = "nordtal-s2";

    private static Docker docker;
    private static DockerOps ops;

    @BeforeAll
    static void connect() {
        final DockerSocket socket = new DockerSocket();
        assumeTrue(socket.isReachable(), "no docker socket - skipping");
        docker = new Docker(socket);
        ops = new DockerOps(docker, PROJECT);
    }

    @Test
    @DisplayName("the same containers the docker command line sees, by compose service name")
    void seesTheSameContainers() throws Exception {
        final Set<String> mine = docker.containers(PROJECT).stream()
                .map(Docker.Container::service)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        final Set<String> theirs = Set.copyOf(cli("docker", "ps", "-a",
                "--filter", "label=com.docker.compose.project=" + PROJECT,
                "--format", "{{ .Label \"com.docker.compose.service\" }}"));

        assumeTrue(!theirs.isEmpty(), "the nordtal-s2 stack is not on this host - skipping");
        assertEquals(theirs, mine);
    }

    @Test
    @DisplayName("running and healthy means the same thing here as in `docker ps`")
    void agreesAboutHealth() throws Exception {
        final RuntimeResult runtime = ops.runtime();
        assertTrue(runtime.reached(), String.valueOf(runtime.message()));
        assumeTrue(!runtime.services().isEmpty(), "no services - skipping");

        // `docker ps` prints the health in its status column, which is the sentence a person reads.
        // Comparing against it is the point: isBack() is what an update run waits on, and if the two
        // ever disagree the run waits for something nobody can see.
        for (final String line : cli("docker", "ps", "-a",
                "--filter", "label=com.docker.compose.project=" + PROJECT,
                "--format", "{{ .Label \"com.docker.compose.service\" }}\t{{ .Status }}")) {
            final String[] parts = line.split("\t", 2);
            final Optional<ServiceRuntime> service = runtime.service(parts[0]);
            assertTrue(service.isPresent(), "runtime() is missing " + parts[0]);

            final String status = parts.length > 1 ? parts[1] : "";
            final boolean cliSaysBack = status.startsWith("Up")
                    && !status.contains("(unhealthy)") && !status.contains("(health: starting)");
            assertEquals(cliSaysBack, service.get().isBack(),
                    parts[0] + ": `docker ps` says \"" + status + "\", runtime() says \""
                            + service.get().describe() + "\"");
        }
    }

    @Test
    @DisplayName("one stats sample has real memory and a CPU delta, not a zero from one-shot")
    void readsStats() throws Exception {
        final Docker.Container running = someRunningContainer();
        final Docker.Stats stats = docker.stats(running.id());

        assertTrue(stats.memoryBytes() > 0, "memory came back as " + stats.memoryBytes());
        // The limit is the host's memory, because no container in this stack sets one - measured
        // 2026-09-12. If that ever changes this assertion is the thing that notices.
        assertTrue(stats.memoryLimitBytes() >= stats.memoryBytes());
        assertTrue(stats.cpuPercent().isPresent(),
                "no CPU delta - is the request sending one-shot after all?");
    }

    @Test
    @DisplayName("a log line from the stream is a log line `docker logs` shows")
    void readsTheSameLog() throws Exception {
        final Docker.Container running = someRunningContainer();
        final Docker.Inspection inspection = docker.inspect(running.id());
        final List<String> mine = docker.recentLines(running.id(), 5, !inspection.tty());
        assumeTrue(!mine.isEmpty(), running.service() + " has no log lines - skipping");

        final List<String> theirs = cli("docker", "logs", "--timestamps", "--tail", "5", running.id());
        assumeTrue(!theirs.isEmpty(), "the command line shows no lines either - skipping");

        // Not an equality of lists: the container keeps writing between the two reads. What has to
        // hold is that a line this client produced is a line the daemon's own client produced -
        // byte for byte, which is what catches a frame header read as text.
        final String candidate = mine.get(mine.size() - 1);
        assertTrue(theirs.contains(candidate) || mine.stream().anyMatch(theirs::contains),
                "none of " + mine + "\nis in " + theirs);
    }

    @Test
    @DisplayName("the console runs a command in a container and brings its output back")
    void execRunsAndAnswers() throws Exception {
        final Docker.Container running = someRunningContainer();
        final Docker.ExecResult answer = docker.exec(running.id(),
                List.of("echo", "steward was here"));

        // Proves the whole hijacked-stream path: create, start, eight-byte frames, decode, and the
        // second request that fetches the exit code.
        assertEquals("steward was here", answer.output().strip());
        assertEquals(0, answer.exitCode());
    }

    @Test
    @DisplayName("a command that fails says so in its exit code, not only in its output")
    void carriesTheExitCodeBack() {
        final Docker.Container running = someRunningContainer();

        // Without the exit code a failed command is indistinguishable from a quiet one - which is
        // how a partial backup file gets renamed over a good one.
        final Docker.ExecResult answer = docker.exec(running.id(),
                List.of("sh", "-c", "echo nope >&2; exit 3"));

        assertEquals(3, answer.exitCode());
        assertFalse(answer.ok());
        assertTrue(answer.output().contains("nope"), answer.output());
    }

    @Test
    @DisplayName("drift appears when a tag is bent by hand, and goes away when it is put back")
    void seesDriftAppearAndGoAgain() throws Exception {
        // Two images that exist, are small, and have nothing to do with this stack.
        cli("docker", "pull", "-q", "alpine:3.20");
        cli("docker", "pull", "-q", "alpine:3.19");
        final String honest = imageIdOf("alpine:3.20");

        assertEquals(ImageResult.State.UP_TO_DATE, ops.check("alpine:3.20", honest).state(),
                "a freshly pulled tag should agree with its registry");

        try {
            // Exactly the situation A24 hid: what runs is not what the tag means any more.
            cli("docker", "tag", "alpine:3.19", "alpine:3.20");
            final DockerOps.ImageCheck bent = ops.check("alpine:3.20", imageIdOf("alpine:3.20"));
            assertEquals(ImageResult.State.OUTDATED, bent.state(),
                    "a tag pointing at another image is drift, and was not seen as drift");
        } finally {
            cli("docker", "pull", "-q", "alpine:3.20");
        }

        assertEquals(ImageResult.State.UP_TO_DATE, ops.check("alpine:3.20", imageIdOf("alpine:3.20")).state(),
                "after pulling the real image back the drift should be gone");
    }

    @Test
    @DisplayName("an image nobody can ask about is UNKNOWN and named, never `up to date`")
    void doesNotCallTheUnknownCurrent() {
        final DockerOps.ImageCheck check =
                ops.check("ghcr.io/nordtal/does-not-exist:latest", "sha256:" + "0".repeat(64));

        assertEquals(ImageResult.State.UNKNOWN, check.state());
        assertNotNull(check.reason(), "an UNKNOWN with no reason is a shrug the report cannot print");
    }

    @Test
    @DisplayName("the daemon's disk usage is readable, which is half of `how full is the box`")
    void readsDiskUsage() {
        final Docker.DiskUsage usage = docker.diskUsage();
        assertTrue(usage.imagesBytes() > 0, "no images take any space, which cannot be true here");
    }

    @Test
    @DisplayName("a container that does not exist is an error with the daemon's own words in it")
    void saysWhatTheDaemonSaid() {
        final DockerException thrown = org.junit.jupiter.api.Assertions.assertThrows(
                DockerException.class, () -> docker.inspect("nordtal-no-such-container"));

        assertEquals(404, thrown.status());
        assertTrue(thrown.getMessage().contains("No such container"), thrown.getMessage());
        assertFalse(thrown.getMessage().isBlank());
    }

    private static Docker.Container someRunningContainer() {
        return docker.containers(PROJECT).stream()
                .filter(Docker.Container::isRunning)
                .findFirst()
                .orElseGet(() -> {
                    assumeTrue(false, "nothing of " + PROJECT + " is running - skipping");
                    return null;
                });
    }

    private static String imageIdOf(final String reference) throws Exception {
        return cli("docker", "image", "inspect", "--format", "{{ .Id }}", reference).get(0);
    }

    /** Runs the docker command line and hands back its output, one line per entry. */
    private static List<String> cli(final String... command) throws IOException, InterruptedException {
        final Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        final List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    lines.add(line);
                }
            }
        }
        process.waitFor();
        return lines;
    }
}
