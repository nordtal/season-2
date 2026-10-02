package eu.nordtal.s2.stewardagent.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import eu.nordtal.s2.internalapi.agent.ImageResult;
import eu.nordtal.s2.internalapi.agent.RuntimeResult;
import eu.nordtal.s2.internalapi.agent.ServiceRuntime;
import eu.nordtal.s2.stewardagent.TestProject;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The docker client against a real daemon, checked against the {@code docker} CLI reading the same one.
 *
 * It skips itself where there is no socket.
 */
class DockerIntegrationTest {

    private static final String PROJECT = TestProject.reading();

    private static Docker docker;
    private static Containers ops;

    @BeforeAll
    static void connect() {
        final DockerSocket socket = new DockerSocket();
        assumeTrue(socket.isReachable(), "no docker socket - skipping");
        docker = new Docker(socket);
        ops = new Containers(docker, PROJECT);
    }

    @Test
    void theSameContainersTheDockerCommandLineSeesByComposeServiceName() throws Exception {
        final Set<String> mine = docker.containers(PROJECT).stream()
                .map(Docker.Container::service)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        final Set<String> theirs = Set.copyOf(cli(
                "docker",
                "ps",
                "-a",
                "--filter",
                "label=com.docker.compose.project=" + PROJECT,
                "--format",
                "{{ .Label \"com.docker.compose.service\" }}"));

        assumeTrue(!theirs.isEmpty(), "the nordtal-s2 stack is not on this host - skipping");
        assertEquals(theirs, mine);
    }

    @Test
    void runningAndHealthyMeansTheSameThingHereAsInDockerPs() throws Exception {
        final RuntimeResult runtime = ops.runtime();
        assertTrue(runtime.reached(), String.valueOf(runtime.message()));
        assumeTrue(!runtime.services().isEmpty(), "no services - skipping");

        // `docker ps` prints the health a person reads; isBack() must agree, or an update run waits on nothing visible.
        for (final String line : cli(
                "docker",
                "ps",
                "-a",
                "--filter",
                "label=com.docker.compose.project=" + PROJECT,
                "--format",
                "{{ .Label \"com.docker.compose.service\" }}\t{{ .Status }}")) {
            final int tab = line.indexOf('\t');
            final String name = tab < 0 ? line : line.substring(0, tab);
            final Optional<ServiceRuntime> service = runtime.service(name);
            assertTrue(service.isPresent(), "runtime() is missing " + name);

            final String status = tab < 0 ? "" : line.substring(tab + 1);
            final boolean cliSaysBack = status.startsWith("Up")
                    && !status.contains("(unhealthy)")
                    && !status.contains("(health: starting)");
            assertEquals(
                    cliSaysBack,
                    service.get().isBack(),
                    name + ": `docker ps` says \"" + status + "\", runtime() says \""
                            + service.get().describe() + "\"");
        }
    }

    @Test
    void oneStatsSampleHasRealMemoryAndACpuDeltaNotAZeroFromOneShot() throws Exception {
        final Docker.Container running = someRunningContainer();
        final Docker.Stats stats = docker.stats(running.id());

        assertTrue(stats.memoryBytes() > 0, "memory came back as " + stats.memoryBytes());
        // The limit is the host's memory, since no container here sets one; this assertion notices if that changes.
        assertTrue(stats.memoryLimitBytes() >= stats.memoryBytes());
        assertTrue(stats.cpuPercent().isPresent(), "no CPU delta - is the request sending one-shot after all?");
    }

    @Test
    void aLogLineFromTheStreamIsALogLineDockerLogsShows() throws Exception {
        final Docker.Container running = someRunningContainer();
        final Docker.Inspection inspection = docker.inspect(running.id());
        final List<String> mine = docker.recentLines(running.id(), 5, !inspection.tty());
        assumeTrue(!mine.isEmpty(), running.service() + " has no log lines - skipping");

        final List<String> theirs = cli("docker", "logs", "--timestamps", "--tail", "5", running.id());
        assumeTrue(!theirs.isEmpty(), "the command line shows no lines either - skipping");

        // Not list equality, since the container keeps writing: a line must match byte-for-byte, catching a bad header.
        final String candidate = mine.get(mine.size() - 1);
        assertTrue(
                theirs.contains(candidate) || mine.stream().anyMatch(theirs::contains),
                "none of " + mine + "\nis in " + theirs);
    }

    @Test
    void theConsoleRunsACommandInAContainerAndBringsItsOutputBack() throws Exception {
        final Docker.Container running = someContainerToActOn();
        final Docker.ExecResult answer = docker.exec(running.id(), List.of("echo", "steward was here"));

        // Proves the whole hijacked-stream path: create, start, eight-byte frames, decode, and the exit-code fetch.
        assertEquals("steward was here", answer.output().strip());
        assertEquals(0, answer.exitCode());
    }

    @Test
    void aCommandThatFailsSaysSoInItsExitCodeNotOnlyInItsOutput() {
        final Docker.Container running = someContainerToActOn();

        // Without the exit code a failed command reads as a quiet one, which is how a partial backup file wins.
        final Docker.ExecResult answer = docker.exec(running.id(), List.of("sh", "-c", "echo nope >&2; exit 3"));

        assertEquals(3, answer.exitCode());
        assertFalse(answer.ok());
        assertTrue(answer.output().contains("nope"), answer.output());
    }

    @Test
    void driftAppearsWhenATagIsBentByHandAndGoesAwayWhenItIsPutBack() throws Exception {
        // It pulls and retags, which changes the daemon's store for everybody on it.
        assumeTrue(TestProject.acting().isPresent(), TestProject.VARIABLE + " names no scratch project - skipping");
        // Two images that exist, are small, and have nothing to do with this stack.
        cli("docker", "pull", "-q", "alpine:3.20");
        cli("docker", "pull", "-q", "alpine:3.19");
        final String honest = imageIdOf("alpine:3.20");

        assertEquals(
                ImageResult.State.UP_TO_DATE,
                ops.check("alpine:3.20", honest).state(),
                "a freshly pulled tag should agree with its registry");

        try {
            // What runs is no longer what the tag means.
            cli("docker", "tag", "alpine:3.19", "alpine:3.20");
            final Containers.ImageCheck bent = ops.check("alpine:3.20", imageIdOf("alpine:3.20"));
            assertEquals(
                    ImageResult.State.OUTDATED,
                    bent.state(),
                    "a tag pointing at another image is drift, and was not seen as drift");
        } finally {
            cli("docker", "pull", "-q", "alpine:3.20");
        }

        assertEquals(
                ImageResult.State.UP_TO_DATE,
                ops.check("alpine:3.20", imageIdOf("alpine:3.20")).state(),
                "after pulling the real image back the drift should be gone");
    }

    @Test
    void anImageNobodyCanAskAboutIsUnknownAndNamedNeverUpToDate() {
        final Containers.ImageCheck check =
                ops.check("ghcr.io/nordtal/does-not-exist:latest", "sha256:" + "0".repeat(64));

        assertEquals(ImageResult.State.UNKNOWN, check.state());
        assertNotNull(check.reason(), "an UNKNOWN with no reason is a shrug the report cannot print");
    }

    @Test
    void theDaemonsDiskUsageIsReadableWhichIsHalfOfHowFullIsTheBox() {
        final Docker.DiskUsage usage = docker.diskUsage();
        assertTrue(usage.imagesBytes() > 0, "no images take any space, which cannot be true here");
    }

    @Test
    void aContainerThatDoesNotExistIsAnErrorWithTheDaemonsOwnWordsInIt() {
        final DockerException thrown = org.junit.jupiter.api.Assertions.assertThrows(
                DockerException.class, () -> docker.inspect("nordtal-no-such-container"));

        assertEquals(404, thrown.status());
        assertTrue(thrown.getMessage().contains("No such container"), thrown.getMessage());
        assertFalse(thrown.getMessage().isBlank());
    }

    /** A running container of the scratch project, since an exec changes what it runs; skipped without one. */
    private static Docker.Container someContainerToActOn() {
        assumeTrue(TestProject.acting().isPresent(), TestProject.VARIABLE + " names no scratch project - skipping");
        return someRunningContainer();
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
        return cli("docker", "image", "inspect", "--format", "{{ .Id }}", reference)
                .get(0);
    }

    /** Runs the docker command line and hands back its output, one line per entry. */
    private static List<String> cli(final String... command) throws IOException, InterruptedException {
        final Process process =
                new ProcessBuilder(command).redirectErrorStream(true).start();
        final List<String> lines = new ArrayList<>();
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
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
