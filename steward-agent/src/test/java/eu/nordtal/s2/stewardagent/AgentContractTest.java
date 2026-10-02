package eu.nordtal.s2.stewardagent;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.common.json.Json;
import eu.nordtal.s2.internalapi.InternalClient;
import eu.nordtal.s2.internalapi.agent.AgentClient;
import eu.nordtal.s2.internalapi.agent.AgentWire;
import eu.nordtal.s2.internalapi.agent.LogFollow;
import eu.nordtal.s2.internalapi.agent.RuntimeResult;
import eu.nordtal.s2.internalapi.agent.Topology;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The contract: steward's typed client against the agent's real routes, over a stand-in daemon. */
class AgentContractTest {

    private static Path scratch;
    private static AgentStandIn agent;
    private static AgentClient client;

    @BeforeAll
    static void start() throws IOException {
        // Short, since a Unix socket path has a length limit the default temp directory can exceed.
        scratch = Files.createTempDirectory(Path.of("/tmp"), "agent");
        agent = new AgentStandIn(scratch, 0, config -> {});
        client = new AgentClient(agent.client());
    }

    @AfterAll
    static void stop() throws IOException {
        agent.close();
        try (var files = Files.walk(scratch)) {
            files.sorted(java.util.Comparator.reverseOrder())
                    .forEach(path -> path.toFile().delete());
        }
    }

    @Test
    void theTopologyIsWhatTheLabelsSay() {
        final AgentWire.Topology topology =
                Json.decode(agent.client().get(AgentWire.TOPOLOGY), AgentWire.Topology.class);
        assertEquals(
                List.of(
                        new AgentWire.Service(
                                "smp",
                                "ghcr.io/nordtal/minecraft:latest",
                                true,
                                true,
                                new Topology.Service(
                                        "smp",
                                        Topology.Kind.PAPER,
                                        List.of("smp", "display-tags", "voicechat"),
                                        List.of("voicechat"),
                                        java.util.Map.of("display-tags", "papermc-display-tags")),
                                null,
                                AgentWire.Renewal.RUN),
                        new AgentWire.Service("steward-agent", "ghcr.io/nordtal/steward-agent:latest", false, false),
                        new AgentWire.Service(
                                "postgres", "postgres:18", false, false, null, null, AgentWire.Renewal.LAST)),
                topology.services());
        assertTrue(topology.hasPlugins("smp"), "the server and its plugins reach steward intact");
        // The backup set is the agent's own mounts, and the stop set the label: nothing else names either.
        assertEquals(List.of("nordtal-s2_mc-smp"), topology.backupVolumes());
        assertEquals(List.of("smp"), topology.stoppedForBackup());
    }

    @Test
    void theContainersComeWithTheirStateAndARunReadsThemAsItsRuntime() {
        final AgentWire.Containers all = client.containers();
        assertTrue(all.reached());
        assertEquals(
                List.of("smp"),
                all.containers().stream().map(AgentWire.Container::service).toList());
        assertTrue(all.containers().getFirst().isRunning());

        final RuntimeResult runtime = client.runtime();
        assertTrue(runtime.reached(), String.valueOf(runtime.message()));
        assertEquals("c0ffee", runtime.services().getFirst().containerId());
    }

    @Test
    void aDaemonThatDoesNotAnswerIsSaidInsteadOfThrown() {
        agent.daemon.broken.set(true);
        try {
            final AgentWire.Containers all = client.containers();
            assertFalse(all.reached());
            assertTrue(String.valueOf(all.message()).contains("not answering"), all.message());
        } finally {
            agent.daemon.broken.set(false);
        }
    }

    @Test
    void oneContainerIsFoundByItsServiceAndAMissingOneIsEmpty() {
        assertEquals("smp", client.container("smp").orElseThrow().service());
        assertTrue(client.container("limbo").isEmpty());
    }

    @Test
    void aConsoleLineReachesTheServerAndARefusalSaysWhichServicesHaveOne() {
        client.console("smp", "list", "Ally");
        assertTrue(agent.daemon.execs.stream().anyMatch(body -> body.contains("[\"mc\",\"list\"]")));

        final InternalClient.Failure refused =
                assertThrows(InternalClient.Failure.class, () -> client.console("postgres", "list", "Ally"));
        assertEquals(400, refused.status());
        assertEquals("postgres has no console. The services with one are smp.", AgentClient.sentence(refused));
    }

    @Test
    void theSamplerHasARoundAtOnceAndNothingAfterItsNewest() throws InterruptedException {
        final Instant giveUp = Instant.now().plusSeconds(10);
        List<AgentWire.Round> rounds = client.samples(null);
        while (rounds.isEmpty() && Instant.now().isBefore(giveUp)) {
            Thread.sleep(100);
            rounds = client.samples(null);
        }
        assertFalse(rounds.isEmpty(), "the first round is taken when the agent starts, not a period later");
        assertTrue(rounds.getLast().services().containsKey("smp"));
        assertEquals(List.of(), client.samples(rounds.getLast().at()));

        final AgentWire.Host host = client.host();
        assertNotNull(host.numbers(), host.unreadable());
    }

    @Test
    void aVolumeIsMeasuredByItsServiceAndAPathOutsideTheVolumesIsNot() throws IOException {
        Files.write(Files.createDirectories(agent.volumes.resolve("smp")).resolve("level.dat"), new byte[64 * 1024]);

        assertTrue(client.disk("smp").orElseThrow() >= 64 * 1024);
        assertEquals(
                404,
                assertThrows(InternalClient.Failure.class, () -> client.disk("postgres"))
                        .status());
        assertEquals(
                404,
                assertThrows(InternalClient.Failure.class, () -> client.disk(".."))
                        .status());
    }

    @Test
    void anArchiveIsListedAndStreamedByItsNameAndOnlyFromInsideTheDirectory() throws IOException {
        final byte[] bytes = "a world".getBytes(StandardCharsets.UTF_8);
        Files.write(agent.backups.resolve("smp-data-20261001T120000Z.tar.zst"), bytes);

        assertTrue(client.archives().stream()
                .anyMatch(archive -> archive.name().equals("smp-data-20261001T120000Z.tar.zst") && !archive.partial()));
        try (InputStream body = client.archive("smp-data-20261001T120000Z.tar.zst")) {
            assertArrayEquals(bytes, body.readAllBytes());
        }
        final InternalClient.Failure outside =
                assertThrows(InternalClient.Failure.class, () -> client.archive("../sources"));
        assertEquals(400, outside.status());
    }

    @Test
    void aLogFollowBringsTheBacklogAndThenTheLiveLine() throws Exception {
        final List<AgentWire.LogEvent> events = new ArrayList<>();
        try (LogFollow follow = client.logs("smp", "10", null)) {
            final Thread reader = Thread.ofVirtual().start(() -> {
                try {
                    follow.read(event -> {
                        synchronized (events) {
                            events.add(event);
                        }
                    });
                } catch (final IOException closed) {
                    // Closed underneath, which is how the test ends it.
                }
            });
            final Instant giveUp = Instant.now().plusSeconds(10);
            while (lines(events) < 2 && Instant.now().isBefore(giveUp)) {
                Thread.sleep(50);
            }
            follow.close();
            reader.join(Duration.ofSeconds(5));
        }
        assertTrue(lines(events) >= 2, "expected the backlog's line and a live one: " + events);
    }

    @Test
    void theWrongSecretIsRefused() {
        final InternalClient stranger =
                new InternalClient(AgentWire.SERVICE, agent.base(), "a-guess", Duration.ofSeconds(5));
        assertEquals(
                401,
                assertThrows(InternalClient.Failure.class, () -> stranger.get(AgentWire.CONTAINERS))
                        .status());
    }

    private static long lines(final List<AgentWire.LogEvent> events) {
        synchronized (events) {
            return events.stream().filter(event -> event.event().equals("line")).count();
        }
    }
}
