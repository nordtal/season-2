package eu.nordtal.s2.stewardagent.measure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import eu.nordtal.s2.internalapi.agent.AgentWire;
import eu.nordtal.s2.stewardagent.TestProject;
import eu.nordtal.s2.stewardagent.docker.Docker;
import eu.nordtal.s2.stewardagent.docker.DockerSocket;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** A round against the real daemon: the host's numbers and one reading per running service, and nothing written. */
class SamplerIntegrationTest {

    @Test
    void oneRoundCarriesTheHostsNumbersAndOneReadingPerRunningService() throws InterruptedException {
        final DockerSocket socket = new DockerSocket();
        assumeTrue(socket.isReachable(), "no docker socket - skipping");
        final Docker docker = new Docker(socket);
        final String project = TestProject.reading();

        final Instant at = Instant.now();
        final AgentWire.Round round;
        try (Sampler sampler = new Sampler(docker, new HostMetrics(), project, Clock.systemUTC())) {
            // Twice: the first round has no previous CPU reading to subtract from.
            sampler.tick(at.minusSeconds(30));
            // With no containers both ticks fit inside one jiffy, and no time passed means no CPU to report.
            Thread.sleep(200);
            round = sampler.tick(at);
            assertEquals(1, sampler.after(at.minusSeconds(1)).size(), "only the round after the instant asked for");
        }
        assertNotNull(round.host(), "the host's numbers never arrived");
        assertNotNull(round.host().cpuPercent(), "the second round has a CPU reading to subtract from");

        final Set<String> services = docker.containers(project).stream()
                .filter(container -> container.service() != null && container.isRunning())
                .map(Docker.Container::service)
                .collect(Collectors.toSet());
        assumeTrue(!services.isEmpty(), "nothing of the stack is running - skipping");
        for (final String service : services) {
            assertTrue(round.services().containsKey(service), service + " is running but has no reading");
        }
    }
}
